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
	"io"
	"net"
	"sync"
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

	// How long a forwarded connection may sit with nothing crossing it. UDP has
	// no close, so without this every DNS lookup would leak a goroutine and a
	// socket for the life of the session.
	idleTimeout = 5 * time.Minute
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

// forward splices two connections and returns when either side is done.
//
// Both directions are needed and both must be able to end the pair: a download
// finishes when the remote closes, an upload when the client does, and waiting
// for the wrong one hangs the transfer.
func forward(a, b net.Conn) {
	defer a.Close()
	defer b.Close()

	done := make(chan struct{}, 2)
	copyOneWay := func(dst, src net.Conn) {
		_ = extendDeadline(dst)
		_, _ = io.Copy(dst, src)
		done <- struct{}{}
	}
	go copyOneWay(a, b)
	go copyOneWay(b, a)
	<-done
}

func extendDeadline(c net.Conn) error {
	return c.SetDeadline(time.Now().Add(idleTimeout))
}
