// Package relaywg is the phone side of Full Mode: a userspace WireGuard
// endpoint that accepts one peer (the PC) and forwards its TCP and UDP out
// through ordinary sockets on the phone.
//
// Contract: docs/adr/0008-full-mode-wireguard-forwarder.md.
//
// The shape matters and is not the usual one. Every WireGuard library for
// Android is client-shaped: it stands up a VpnService and routes the *device's*
// traffic to a remote peer. Relay needs the mirror image — the phone is the
// server, the PC is the peer, and the phone has no root, no iptables and no NAT
// to forward packets with.
//
// So the tunnel is terminated in userspace by wireguard-go, and a gVisor
// network stack turns the peer's inbound IP packets into normal outbound
// sockets on the phone. Those sockets ride the phone's default network — and
// therefore its VPN — exactly as the Fast Mode SOCKS path does. No VpnService,
// no root, no packet forwarding.
//
// Crypto is entirely wireguard-go's. Nothing here implements any part of the
// WireGuard protocol; this is configuration, lifecycle, and the dial-out glue.
package relaywg

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/device"
)

const (
	// The addresses the phone's end of the tunnel answers on (IPv4 + IPv6).
	tunnelAddress   = "10.13.37.1"
	tunnelAddressV6 = "fd13:37::1"

	// Matches WireGuard's own default and leaves room under a 1500-byte path.
	mtu = 1420

	// How long a forwarded connection may sit with nothing crossing it.
	//
	// TCP says when it is done, so this is only the backstop for a peer that
	// vanished without a FIN.
	defaultTCPIdleTimeout = 5 * time.Minute

	// UDP never says it is done, so this is the only thing that reaps it -- and
	// a browsing session opens one flow per DNS lookup, each holding two
	// goroutines, a socket and a 64 KB buffer until it is reaped. Five minutes
	// of that on a phone is hundreds of megabytes and thousands of goroutines
	// for exchanges that ended in milliseconds. A minute is still generous for
	// the flows that legitimately persist: QUIC and games keep themselves
	// alive, and anything that does not is finished.
	defaultUDPIdleTimeout = 60 * time.Second
)

// The timeouts the forwarders actually use.
//
// Variables rather than constants for one reason: the reliability tests have to
// watch a connection cross an idle timeout and come out the other side, and at
// five minutes each that is a suite nobody runs. Nothing outside the tests ever
// writes them, and the values are the constants above.
var (
	tcpIdleTimeout = defaultTCPIdleTimeout
	udpIdleTimeout = defaultUDPIdleTimeout
)

// Endpoint is one running Full Mode session. It is safe to call Stop on an
// Endpoint that never started, and to call it twice: teardown runs on paths
// that are already unwinding from an error, and a teardown that panics there
// takes the app with it.
type Endpoint struct {
	mu     sync.Mutex
	dev    *device.Device
	tun    *netTun
	closed bool
}

// Start brings up the endpoint from a wireguard-go IPC configuration — the
// same "key=value\n" form wg(8) uses, produced on the Kotlin side by WgConfig.
//
// The listen port comes from the configuration itself (listen_port=), the same
// way wg(8) takes it, so there is one source of truth for it rather than two
// that can disagree.
func Start(ipcConfig string) (*Endpoint, error) {
	return StartVia(ipcConfig, "")
}

// StartVia is Start with somewhere else to send the forwarded traffic.
//
// upstreamProxy is a SOCKS5 address — in practice a VPN client's own local
// port, "127.0.0.1:10808" and the like — or empty for the phone's default
// route, which is what Start means and what every release before this did.
//
// It exists because of a trade the app could not otherwise escape. An Android
// VPN captures by UID, so it swallows Relay's replies to the PC and the pairing
// never completes; excluding Relay in the VPN's per-app list fixes that and
// takes Relay out of the tunnel, so the PC then gets the phone's connection and
// not the phone's VPN. Sending through the VPN's own proxy is how both fit:
// Relay stays outside the tunnel so it can answer the LAN, and the traffic it
// carries goes in through the front door. See upstream.go.
//
// Separate from Start rather than a second parameter on it because Start is
// exported through gomobile and callers on the Kotlin side are generated
// against its signature.
func StartVia(ipcConfig, upstreamProxy string) (*Endpoint, error) {
	if ipcConfig == "" {
		return nil, errors.New("relaywg: empty configuration")
	}

	out, err := newUpstream(upstreamProxy)
	if err != nil {
		return nil, err
	}

	tunDevice, err := newNetTun(mtu)
	if err != nil {
		return nil, err
	}
	tunDevice.setUpstream(out)
	// Installed before the tunnel comes up, so the first packet from the peer
	// already has somewhere to go.
	tunDevice.installForwarders()

	dev := device.NewDevice(tunDevice, newResilientBind(), device.NewLogger(
		device.LogLevelError, "relaywg: "))

	if err := dev.IpcSet(ipcConfig); err != nil {
		dev.Close()
		return nil, fmt.Errorf("relaywg: rejected configuration: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("relaywg: could not bring the tunnel up: %w", err)
	}

	return &Endpoint{dev: dev, tun: tunDevice}, nil
}

// SetUpstream changes where the endpoint forwards new flows, without touching
// the tunnel: same keys, same port, same peer, no handshake lost.
//
// This is what lets the phone pick its route while sharing rather than once at
// start. A VPN client can open its local proxy after Relay started, or close it
// -- the user switched modes, or the app was killed -- and the right answer for
// the PC changes with it. Restarting the endpoint to follow would drop the
// laptop for as long as a rehandshake takes and reset every connection on it;
// this changes the next dial and nothing else.
//
// Flows already open keep the path they were dialled on. A TCP stream cannot
// move to a different egress mid-life, and a UDP flow is reaped within a minute
// of going quiet (udpIdleTimeout), so everything converges on the new route
// without anyone's download being cut.
//
// The address is validated exactly as StartVia validates it, and a bad one
// leaves the current route in place: a typo must never become "forward
// nowhere".
func (e *Endpoint) SetUpstream(upstreamProxy string) error {
	if e == nil {
		return errors.New("relaywg: no endpoint is running")
	}
	out, err := newUpstream(upstreamProxy)
	if err != nil {
		return err
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed || e.tun == nil {
		return errors.New("relaywg: the endpoint has stopped")
	}
	e.tun.setUpstream(out)
	return nil
}

// Stop tears the endpoint down. Idempotent by design — see the type comment.
func (e *Endpoint) Stop() {
	if e == nil {
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return
	}
	e.closed = true
	if e.dev != nil {
		// Closing the device closes the tun it was given, which stops the
		// forwarders and every socket they opened.
		e.dev.Close()
		e.dev = nil
	}
	e.tun = nil
}

// LastHandshakeUnix reports when any configured peer most recently completed a
// handshake, in seconds since the epoch, or 0 if none ever has.
func (e *Endpoint) LastHandshakeUnix() int64 {
	var best int64
	for _, p := range e.peersStats() {
		if p.LastHandshake > best {
			best = p.LastHandshake
		}
	}
	return best
}

// BytesReceived and BytesSent are the sum across all peers, from the phone's
// point of view: received is what clients sent up, sent is what went back down.
func (e *Endpoint) BytesReceived() int64 {
	var sum int64
	for _, p := range e.peersStats() {
		sum += p.RxBytes
	}
	return sum
}

func (e *Endpoint) BytesSent() int64 {
	var sum int64
	for _, p := range e.peersStats() {
		sum += p.TxBytes
	}
	return sum
}

// PeerStats summarizes one WireGuard peer from device.IpcGet().
type PeerStats struct {
	PublicKey     string `json:"publicKey"`
	Endpoint      string `json:"endpoint"`
	LastHandshake int64  `json:"lastHandshake"`
	RxBytes       int64  `json:"rxBytes"`
	TxBytes       int64  `json:"txBytes"`
}

func (e *Endpoint) peersStats() []PeerStats {
	if e == nil {
		return nil
	}
	e.mu.Lock()
	dev := e.dev
	e.mu.Unlock()
	if dev == nil {
		return nil
	}
	status, err := dev.IpcGet()
	if err != nil {
		return nil
	}
	return parsePeersIpc(status)
}

func parsePeersIpc(status string) []PeerStats {
	var peers []PeerStats
	var cur *PeerStats
	for _, line := range strings.Split(status, "\n") {
		key, value, found := strings.Cut(strings.TrimSpace(line), "=")
		if !found {
			continue
		}
		switch key {
		case "public_key":
			peers = append(peers, PeerStats{PublicKey: value})
			cur = &peers[len(peers)-1]
		case "endpoint":
			if cur != nil {
				cur.Endpoint = value
			}
		case "last_handshake_time_sec":
			if cur != nil {
				if v, err := strconv.ParseInt(value, 10, 64); err == nil {
					cur.LastHandshake = v
				}
			}
		case "rx_bytes":
			if cur != nil {
				if v, err := strconv.ParseInt(value, 10, 64); err == nil {
					cur.RxBytes = v
				}
			}
		case "tx_bytes":
			if cur != nil {
				if v, err := strconv.ParseInt(value, 10, 64); err == nil {
					cur.TxBytes = v
				}
			}
		}
	}
	return peers
}

// AddPeer dynamically adds or updates a WireGuard peer without interrupting active peers.
func (e *Endpoint) AddPeer(publicKeyHex, allowedIPs string) error {
	if e == nil {
		return errors.New("relaywg: no endpoint is running")
	}
	rawKey, err := hex.DecodeString(strings.TrimSpace(publicKeyHex))
	if err != nil || len(rawKey) != 32 {
		return errors.New("relaywg: invalid hex peer public key")
	}
	var b strings.Builder
	b.WriteString("public_key=")
	b.WriteString(strings.ToLower(strings.TrimSpace(publicKeyHex)))
	b.WriteByte('\n')
	b.WriteString("replace_allowed_ips=true\n")
	count := 0
	for _, cidr := range strings.Split(allowedIPs, ",") {
		cidr = strings.TrimSpace(cidr)
		if cidr == "" {
			continue
		}
		if _, _, err := net.ParseCIDR(cidr); err != nil {
			return fmt.Errorf("relaywg: invalid allowed_ip %q: %w", cidr, err)
		}
		b.WriteString("allowed_ip=")
		b.WriteString(cidr)
		b.WriteByte('\n')
		count++
	}
	if count == 0 {
		return errors.New("relaywg: peer requires at least one allowed_ip")
	}

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed || e.dev == nil {
		return errors.New("relaywg: the endpoint has stopped")
	}
	return e.dev.IpcSet(b.String())
}

// RemovePeer removes a peer from the running WireGuard device without touching other peers.
func (e *Endpoint) RemovePeer(publicKeyHex string) error {
	if e == nil {
		return errors.New("relaywg: no endpoint is running")
	}
	rawKey, err := hex.DecodeString(strings.TrimSpace(publicKeyHex))
	if err != nil || len(rawKey) != 32 {
		return errors.New("relaywg: invalid hex peer public key")
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed || e.dev == nil {
		return errors.New("relaywg: the endpoint has stopped")
	}
	return e.dev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", strings.ToLower(strings.TrimSpace(publicKeyHex))))
}

func (e *Endpoint) PeerCount() int {
	return len(e.peersStats())
}

func (e *Endpoint) PeersStatsJSON() string {
	peers := e.peersStats()
	if peers == nil {
		peers = []PeerStats{}
	}
	raw, err := json.Marshal(peers)
	if err != nil {
		return "[]"
	}
	return string(raw)
}

func (e *Endpoint) ActiveFlowsJSON() string {
	if e == nil {
		return "[]"
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t == nil || t.flows == nil {
		return "[]"
	}
	return t.flows.snapshotJSON()
}

func (e *Endpoint) SetTunnelDNS(customDNS, dohURL string) error {
	if e == nil {
		return errors.New("relaywg: no endpoint is running")
	}
	e.mu.Lock()
	t := e.tun
	closed := e.closed
	e.mu.Unlock()
	if closed || t == nil || t.dns == nil {
		return errors.New("relaywg: the endpoint has stopped")
	}
	return t.dns.configure(customDNS, dohURL, t.dial)
}

func (e *Endpoint) SetTrafficLimits(upBytesPerSec, downBytesPerSec, quotaBytes int64) {
	if e == nil {
		return
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t != nil && t.limiter != nil {
		t.limiter.setLimits(upBytesPerSec, downBytesPerSec, quotaBytes)
	}
}

func (e *Endpoint) QuotaUsedBytes() int64 {
	if e == nil {
		return 0
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t == nil || t.limiter == nil {
		return 0
	}
	used, _, _ := t.limiter.status()
	return used
}

func (e *Endpoint) QuotaExceeded() bool {
	if e == nil {
		return false
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t == nil || t.limiter == nil {
		return false
	}
	_, _, ex := t.limiter.status()
	return ex
}

func (e *Endpoint) SetTetherTTL(ttl int) {
	if e == nil {
		return
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t != nil {
		t.tetherTTL.Store(int32(ttl))
	}
}

func (e *Endpoint) EnableICMPRelay(on bool) bool {
	if e == nil {
		return false
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t == nil || t.icmp == nil {
		return false
	}
	ref := t.out.Load()
	if on && ref != nil {
		if _, isSocks := ref.upstream.(socks5); isSocks {
			t.icmp.setEnabled(false)
			return false
		}
	}
	t.icmp.setEnabled(on)
	return t.icmp.active()
}

func (e *Endpoint) AddInboundForward(listenPort int, targetIP string, targetPort int, proto string) error {
	if e == nil {
		return errors.New("relaywg: no endpoint is running")
	}
	e.mu.Lock()
	t := e.tun
	closed := e.closed
	e.mu.Unlock()
	if closed || t == nil || t.forwards == nil {
		return errors.New("relaywg: the endpoint has stopped")
	}
	return t.forwards.add(listenPort, targetIP, targetPort, proto)
}

func (e *Endpoint) RemoveInboundForward(listenPort int, proto string) error {
	if e == nil {
		return nil
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t == nil || t.forwards == nil {
		return nil
	}
	return t.forwards.remove(listenPort, proto)
}

func (e *Endpoint) InboundForwardsJSON() string {
	if e == nil {
		return "[]"
	}
	e.mu.Lock()
	t := e.tun
	e.mu.Unlock()
	if t == nil || t.forwards == nil {
		return "[]"
	}
	return t.forwards.listJSON()
}

// spliceBuffers backs every forwarded connection's two copy loops.
//
// io.Copy would allocate 32 KB per direction per connection. On a phone
// carrying a laptop's whole browsing session that is a steady stream of
// garbage to collect, on the one CPU that measurement showed to be the limit.
var spliceBuffers = sync.Pool{
	New: func() any {
		b := make([]byte, 64*1024)
		return &b
	},
}

// forward splices two connections and returns when either side is done.
//
// Both directions are needed and both must be able to end the pair: a download
// finishes when the remote closes, an upload when the client does, and waiting
// for the wrong one hangs the transfer.
func forward(a, b net.Conn, idle time.Duration) {
	forwardTracked(a, b, idle, context.Background(), nil, nil, nil)
}

func forwardTracked(
	tunConn, remoteConn net.Conn,
	idle time.Duration,
	ctx context.Context,
	flows *flowTracker,
	limiter *trafficLimiter,
	entry *flowEntry,
) {
	defer tunConn.Close()
	defer remoteConn.Close()

	// One clock for the pair, not one per direction. A download is silent
	// upstream for its whole length and a upload is silent downstream, so a
	// per-direction timeout reaps exactly the transfers it exists to protect.
	var seen activity
	seen.mark()

	done := make(chan struct{}, 2)
	go copyUntilIdle(remoteConn, tunConn, idle, &seen, done, ctx, flows, limiter, entry, dirUp)
	go copyUntilIdle(tunConn, remoteConn, idle, &seen, done, ctx, flows, limiter, entry, dirDown)
	<-done
}

// activity is when either direction of a pair last moved a byte.
type activity struct {
	nanos atomic.Int64
}

func (a *activity) mark() { a.nanos.Store(time.Now().UnixNano()) }

func (a *activity) last() time.Time { return time.Unix(0, a.nanos.Load()) }

func (a *activity) idleFor() time.Duration { return time.Since(a.last()) }

// copyUntilIdle copies until the source ends, the destination fails, or nothing
// crosses for [idle].
//
// The deadline moves forward as data flows, which is what an idle timeout is
// and what this was documented to be. What it actually did was call SetDeadline
// once, to five minutes from the moment the connection opened, and never touch
// it again -- so every forwarded connection was torn down five minutes after it
// started no matter how busy it was. A large download, a video call, an SSH
// session and a websocket all died at the same mark, which from the outside is
// indistinguishable from a flaky network, and is exactly what a user reported
// as instability.
func copyUntilIdle(
	dst, src net.Conn,
	idle time.Duration,
	seen *activity,
	done chan<- struct{},
	ctx context.Context,
	flows *flowTracker,
	limiter *trafficLimiter,
	entry *flowEntry,
	dir trafficDirection,
) {
	defer func() { done <- struct{}{} }()

	// Pooled, and larger than io.Copy's default 32 KB. io.Copy allocates a
	// fresh buffer for every call, which is two allocations per connection on a
	// device that is already the bottleneck; at 64 KB it also halves the number
	// of round trips through the netstack per megabyte.
	buf := spliceBuffers.Get().(*[]byte)
	defer spliceBuffers.Put(buf)

	// The deadline currently armed on the sockets. Re-arming allocates a
	// runtime timer, and doing it on every read cost sixty allocations per
	// megabyte for no benefit -- the deadline only has to be roughly right,
	// because an early expiry is caught below and simply re-armed.
	var armed time.Time
	for {
		// Armed from when the pair last moved, so a direction that is quiet
		// because the other one is busy wakes up and goes back to waiting.
		if want := seen.last().Add(idle); want.Sub(armed) > idle/2 {
			armed = want
			_ = src.SetReadDeadline(want)
			_ = dst.SetWriteDeadline(want)
		}

		n, readErr := src.Read(*buf)
		if n > 0 {
			seen.mark()
			if limiter != nil {
				if err := limiter.consume(ctx, dir, n); err != nil {
					return
				}
			}
			if _, writeErr := dst.Write((*buf)[:n]); writeErr != nil {
				return
			}
			if flows != nil {
				if dir == dirUp {
					flows.addUp(entry, int64(n))
				} else {
					flows.addDown(entry, int64(n))
				}
			}
			seen.mark()
		}
		if readErr != nil {
			// A timeout only ends things if the whole pair has gone quiet. The
			// first version of this checked only its own direction, and the
			// test that caught it is the one worth keeping: a download is
			// silent upstream from beginning to end, so that version killed
			// every download at the timeout instead of at five minutes, which
			// is a worse bug than the one it was fixing.
			var timeout net.Error
			if errors.As(readErr, &timeout) && timeout.Timeout() && seen.idleFor() < idle {
				continue
			}
			return
		}
	}
}
