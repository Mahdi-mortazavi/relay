package relaywg

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
)

// TcpBridge tunnels WireGuard UDP packets over a 2-byte length-prefixed TCP
// stream so adb forward (TCP-only) or AP-isolated networks can carry Full Mode.
type TcpBridge struct {
	ln     net.Listener
	port   int
	wgPort int
	closed atomic.Bool
	wg     sync.WaitGroup

	mu    sync.Mutex
	conns map[net.Conn]struct{}
}

// StartTcpBridge binds listenAddr (e.g. "127.0.0.1:51821" or "127.0.0.1:0")
// and bridges incoming TCP connections to 127.0.0.1:<wgPort> UDP.
func StartTcpBridge(listenAddr string, wgPort int) (*TcpBridge, error) {
	if wgPort < 1 || wgPort > 65535 {
		return nil, fmt.Errorf("relaywg: invalid WireGuard port %d", wgPort)
	}
	if listenAddr == "" {
		listenAddr = "127.0.0.1:0"
	}
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		return nil, fmt.Errorf("relaywg: tcp bridge listen %q: %w", listenAddr, err)
	}
	tcpAddr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		_ = ln.Close()
		return nil, fmt.Errorf("relaywg: tcp bridge non-TCP listener address")
	}

	b := &TcpBridge{
		ln:     ln,
		port:   tcpAddr.Port,
		wgPort: wgPort,
		conns:  make(map[net.Conn]struct{}),
	}
	b.wg.Add(1)
	go b.acceptLoop()
	return b, nil
}

func (b *TcpBridge) Port() int {
	if b == nil {
		return 0
	}
	return b.port
}

func (b *TcpBridge) trackConn(c net.Conn) bool {
	if c == nil {
		return false
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed.Load() {
		return false
	}
	if b.conns == nil {
		b.conns = make(map[net.Conn]struct{})
	}
	b.conns[c] = struct{}{}
	return true
}

func (b *TcpBridge) untrackConn(c net.Conn) {
	if c == nil {
		return
	}
	b.mu.Lock()
	delete(b.conns, c)
	b.mu.Unlock()
}

func (b *TcpBridge) Stop() {
	if b == nil {
		return
	}
	if !b.closed.Swap(true) {
		_ = b.ln.Close()

		b.mu.Lock()
		for c := range b.conns {
			_ = c.Close()
		}
		b.conns = nil
		b.mu.Unlock()
	}
	b.wg.Wait()
}

func (b *TcpBridge) acceptLoop() {
	defer b.wg.Done()
	target, err := net.ResolveUDPAddr("udp4", fmt.Sprintf("127.0.0.1:%d", b.wgPort))
	if err != nil {
		return
	}
	for !b.closed.Load() {
		conn, err := b.ln.Accept()
		if err != nil {
			return
		}
		if tc, ok := conn.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}
		if !b.trackConn(conn) {
			_ = conn.Close()
			return
		}
		b.wg.Add(1)
		go b.handleClient(conn, target)
	}
}

func (b *TcpBridge) handleClient(tcpConn net.Conn, target *net.UDPAddr) {
	defer b.wg.Done()
	defer func() {
		b.untrackConn(tcpConn)
		_ = tcpConn.Close()
	}()

	udpConn, err := net.DialUDP("udp4", nil, target)
	if err != nil {
		return
	}
	if !b.trackConn(udpConn) {
		_ = udpConn.Close()
		return
	}
	defer func() {
		b.untrackConn(udpConn)
		_ = udpConn.Close()
	}()

	done := make(chan struct{}, 2)

	// TCP frames -> WireGuard UDP
	go func() {
		defer func() { done <- struct{}{} }()
		var hdr [2]byte
		buf := make([]byte, 65536)
		for !b.closed.Load() {
			if _, err := io.ReadFull(tcpConn, hdr[:]); err != nil {
				return
			}
			n := int(binary.BigEndian.Uint16(hdr[:]))
			if n == 0 {
				continue
			}
			if _, err := io.ReadFull(tcpConn, buf[:n]); err != nil {
				return
			}
			if _, err := udpConn.Write(buf[:n]); err != nil {
				return
			}
		}
	}()

	// WireGuard UDP -> TCP frames
	go func() {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 65536)
		frame := make([]byte, 2+65536)
		for !b.closed.Load() {
			n, err := udpConn.Read(buf)
			if err != nil {
				return
			}
			if n == 0 || n > 65535 {
				continue
			}
			binary.BigEndian.PutUint16(frame[:2], uint16(n))
			copy(frame[2:2+n], buf[:n])
			if _, err := tcpConn.Write(frame[:2+n]); err != nil {
				return
			}
		}
	}()

	<-done
	_ = tcpConn.Close()
	_ = udpConn.Close()
	<-done
}
