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
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
)

const (
	// The address the phone's end of the tunnel answers on. Fixed rather than
	// configurable: it is invisible to the user, it never leaves the tunnel,
	// and one less thing in the QR is one less thing to get wrong.
	tunnelAddress = "10.13.37.1"

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
	if ipcConfig == "" {
		return nil, errors.New("relaywg: empty configuration")
	}

	tunDevice, err := newNetTun(mtu)
	if err != nil {
		return nil, err
	}
	// Installed before the tunnel comes up, so the first packet from the peer
	// already has somewhere to go.
	tunDevice.installForwarders()

	dev := device.NewDevice(tunDevice, conn.NewDefaultBind(), device.NewLogger(
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

// LastHandshakeUnix reports when the peer last completed a handshake, in
// seconds since the epoch, or 0 if it never has.
//
// This is how the phone knows a laptop actually arrived. Fast Mode counts
// accepted sockets; Full Mode has none to count — the peer's traffic is UDP to
// a port that answers whether anyone is there or not — so without asking the
// device, the screen would sit on "waiting for a PC" through an entire
// download. WireGuard rekeys every two minutes while traffic flows, so a recent
// handshake is a live peer, not merely one that once connected.
//
// Read from the device rather than remembered here: a second copy of this state
// is a second thing that can disagree with the tunnel.
func (e *Endpoint) LastHandshakeUnix() int64 {
	return e.statValue("last_handshake_time_sec")
}

// BytesReceived and BytesSent are counted from the phone's point of view:
// received is what the laptop sent up, sent is what went back down.
func (e *Endpoint) BytesReceived() int64 { return e.statValue("rx_bytes") }
func (e *Endpoint) BytesSent() int64     { return e.statValue("tx_bytes") }

// statValue pulls one number out of the device's IPC status. Returns 0 for
// anything it cannot read: this is called once a second to drive a label, and
// an error path there would be noise, not information.
func (e *Endpoint) statValue(name string) int64 {
	if e == nil {
		return 0
	}
	e.mu.Lock()
	dev := e.dev
	e.mu.Unlock()
	if dev == nil {
		return 0
	}

	status, err := dev.IpcGet()
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(status, "\n") {
		key, value, found := strings.Cut(strings.TrimSpace(line), "=")
		if !found || key != name {
			continue
		}
		parsed, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			return 0
		}
		return parsed
	}
	return 0
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
	defer a.Close()
	defer b.Close()

	// One clock for the pair, not one per direction. A download is silent
	// upstream for its whole length and a upload is silent downstream, so a
	// per-direction timeout reaps exactly the transfers it exists to protect.
	var seen activity
	seen.mark()

	done := make(chan struct{}, 2)
	go copyUntilIdle(a, b, idle, &seen, done)
	go copyUntilIdle(b, a, idle, &seen, done)
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
func copyUntilIdle(dst, src net.Conn, idle time.Duration, seen *activity, done chan<- struct{}) {
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
			if _, writeErr := dst.Write((*buf)[:n]); writeErr != nil {
				return
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
