package main

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// validateWgTcpBridgeAddr ensures -wg-tcp-bridge targets a loopback host
// (127.0.0.1, ::1, or localhost) because outbound WFP permits only loopback
// for TCP here.
func validateWgTcpBridgeAddr(remoteTcpAddr string) (string, error) {
	trimmed := strings.TrimSpace(remoteTcpAddr)
	if trimmed == "" {
		return "", fmt.Errorf("-wg-tcp-bridge address cannot be empty")
	}
	host, portStr, err := net.SplitHostPort(trimmed)
	if err != nil {
		return "", fmt.Errorf("invalid -wg-tcp-bridge address %q: %w", remoteTcpAddr, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return "", fmt.Errorf("invalid -wg-tcp-bridge port in %q", remoteTcpAddr)
	}
	if !strings.EqualFold(host, "localhost") {
		ip, err := netip.ParseAddr(host)
		if err != nil || ip.Zone() != "" || !ip.Unmap().IsLoopback() {
			return "", fmt.Errorf("-wg-tcp-bridge host %q must be loopback (127.0.0.1, ::1, or localhost) because outbound WFP permits only loopback TCP", host)
		}
	}
	return trimmed, nil
}

// udpToTcpBridge listens on 127.0.0.1:0 UDP so WireGuard can talk to it as a
// normal endpoint while carrying every WireGuard datagram over a length-framed
// TCP stream (e.g. adb forward tcp:51821 tcp:51821).
type udpToTcpBridge struct {
	udpConn    *net.UDPConn
	localAddr  string
	remoteAddr string
	closed     atomic.Bool

	mu       sync.Mutex
	tcpConn  net.Conn
	peerAddr *net.UDPAddr
}

func startUdpToTcpBridge(remoteTcpAddr string) (*udpToTcpBridge, error) {
	validatedAddr, err := validateWgTcpBridgeAddr(remoteTcpAddr)
	if err != nil {
		return nil, err
	}
	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		return nil, fmt.Errorf("local bridge udp listen: %w", err)
	}
	b := &udpToTcpBridge{
		udpConn:    udpConn,
		localAddr:  udpConn.LocalAddr().String(),
		remoteAddr: validatedAddr,
	}
	// Establish the initial TCP connection right away so startup fails fast if
	// adb forward is not active.
	conn, err := net.DialTimeout("tcp", validatedAddr, 5*time.Second)
	if err != nil {
		_ = udpConn.Close()
		return nil, fmt.Errorf("dial wg tcp bridge %q: %w", validatedAddr, err)
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	b.tcpConn = conn

	go b.pumpUdpToTcp()
	go b.pumpTcpToUdp()
	return b, nil
}

func (b *udpToTcpBridge) LocalEndpoint() string {
	if b == nil {
		return ""
	}
	return b.localAddr
}

func (b *udpToTcpBridge) Close() {
	if b == nil || b.closed.Swap(true) {
		return
	}
	_ = b.udpConn.Close()
	b.mu.Lock()
	if b.tcpConn != nil {
		_ = b.tcpConn.Close()
		b.tcpConn = nil
	}
	b.mu.Unlock()
}

func (b *udpToTcpBridge) ensureTcp() (net.Conn, error) {
	b.mu.Lock()
	c := b.tcpConn
	b.mu.Unlock()
	if c != nil {
		return c, nil
	}
	conn, err := net.DialTimeout("tcp", b.remoteAddr, 3*time.Second)
	if err != nil {
		return nil, err
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	b.mu.Lock()
	if b.closed.Load() {
		b.mu.Unlock()
		_ = conn.Close()
		return nil, net.ErrClosed
	}
	if b.tcpConn != nil {
		_ = conn.Close()
		conn = b.tcpConn
	} else {
		b.tcpConn = conn
	}
	b.mu.Unlock()
	return conn, nil
}

func (b *udpToTcpBridge) dropTcp(bad net.Conn) {
	b.mu.Lock()
	if b.tcpConn == bad {
		b.tcpConn = nil
	}
	b.mu.Unlock()
	if bad != nil {
		_ = bad.Close()
	}
}

func (b *udpToTcpBridge) pumpUdpToTcp() {
	buf := make([]byte, 65536)
	frame := make([]byte, 2+65536)
	for !b.closed.Load() {
		n, from, err := b.udpConn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n == 0 || n > 65535 {
			continue
		}
		b.mu.Lock()
		b.peerAddr = from
		b.mu.Unlock()

		conn, err := b.ensureTcp()
		if err != nil {
			continue
		}
		binary.BigEndian.PutUint16(frame[:2], uint16(n))
		copy(frame[2:2+n], buf[:n])
		if _, err := conn.Write(frame[:2+n]); err != nil {
			b.dropTcp(conn)
		}
	}
}

func (b *udpToTcpBridge) pumpTcpToUdp() {
	var hdr [2]byte
	buf := make([]byte, 65536)
	for !b.closed.Load() {
		conn, err := b.ensureTcp()
		if err != nil {
			time.Sleep(250 * time.Millisecond)
			continue
		}
		if _, err := io.ReadFull(conn, hdr[:]); err != nil {
			b.dropTcp(conn)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		n := int(binary.BigEndian.Uint16(hdr[:]))
		if n == 0 {
			continue
		}
		if _, err := io.ReadFull(conn, buf[:n]); err != nil {
			b.dropTcp(conn)
			continue
		}
		b.mu.Lock()
		peer := b.peerAddr
		b.mu.Unlock()
		if peer != nil {
			_, _ = b.udpConn.WriteToUDP(buf[:n], peer)
		}
	}
}

func replaceIpcEndpoint(ipcConfig, newEndpoint string) string {
	var out strings.Builder
	replaced := false
	for _, line := range strings.Split(ipcConfig, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "endpoint=") {
			out.WriteString("endpoint=")
			out.WriteString(newEndpoint)
			out.WriteByte('\n')
			replaced = true
		} else {
			out.WriteString(trimmed)
			out.WriteByte('\n')
		}
	}
	if !replaced {
		out.WriteString("endpoint=")
		out.WriteString(newEndpoint)
		out.WriteByte('\n')
	}
	return out.String()
}
