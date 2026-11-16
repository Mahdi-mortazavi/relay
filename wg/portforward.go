package relaywg

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
)

// InboundForwardRule represents one active LAN/phone-to-PC port forward.
type InboundForwardRule struct {
	ListenPort int    `json:"listenPort"`
	TargetIP   string `json:"targetIp"`
	TargetPort int    `json:"targetPort"`
	Proto      string `json:"proto"`
}

type activeForwardKey struct {
	port  int
	proto string
}

type activeForward struct {
	rule  InboundForwardRule
	close func()
}

type portForwardManager struct {
	tun   *netTun
	mu    sync.Mutex
	rules map[activeForwardKey]*activeForward
}

func newPortForwardManager(tun *netTun) *portForwardManager {
	return &portForwardManager{
		tun:   tun,
		rules: make(map[activeForwardKey]*activeForward),
	}
}

func (m *portForwardManager) add(listenPort int, targetIP string, targetPort int, proto string) error {
	if m == nil || m.tun == nil || m.tun.stack == nil {
		return fmt.Errorf("relaywg: tunnel stack not ready")
	}
	if listenPort < 1 || listenPort > 65535 || targetPort < 1 || targetPort > 65535 {
		return fmt.Errorf("relaywg: port out of range (%d -> %d)", listenPort, targetPort)
	}
	ip := net.ParseIP(strings.TrimSpace(targetIP))
	if ip == nil {
		return fmt.Errorf("relaywg: invalid target IP %q", targetIP)
	}
	proto = strings.ToLower(strings.TrimSpace(proto))
	if proto == "" {
		proto = "tcp"
	}
	if proto == "both" {
		if err := m.add(listenPort, targetIP, targetPort, "tcp"); err != nil {
			return err
		}
		if err := m.add(listenPort, targetIP, targetPort, "udp"); err != nil {
			_ = m.remove(listenPort, "tcp")
			return err
		}
		return nil
	}
	if proto != "tcp" && proto != "udp" {
		return fmt.Errorf("relaywg: unsupported protocol %q (use tcp, udp, or both)", proto)
	}

	var netProto tcpip.NetworkProtocolNumber
	var targetAddr tcpip.Address
	if v4 := ip.To4(); v4 != nil {
		netProto = ipv4.ProtocolNumber
		targetAddr = tcpip.AddrFrom4Slice(v4)
	} else {
		netProto = ipv6.ProtocolNumber
		targetAddr = tcpip.AddrFrom16Slice(ip.To16())
	}

	fullAddr := tcpip.FullAddress{
		NIC:  nicID,
		Addr: targetAddr,
		Port: uint16(targetPort),
	}
	rule := InboundForwardRule{
		ListenPort: listenPort,
		TargetIP:   ip.String(),
		TargetPort: targetPort,
		Proto:      proto,
	}

	key := activeForwardKey{port: listenPort, proto: proto}
	m.mu.Lock()
	if old, ok := m.rules[key]; ok {
		if old.rule == rule {
			m.mu.Unlock()
			return nil
		}
		old.close()
		delete(m.rules, key)
	}

	var stopFn func()
	if proto == "tcp" {
		ln, err := net.Listen("tcp", fmt.Sprintf(":%d", listenPort))
		if err != nil {
			m.mu.Unlock()
			return fmt.Errorf("relaywg: listen tcp:%d failed: %w", listenPort, err)
		}
		stopFn = func() { _ = ln.Close() }
		go m.serveTCP(ln, fullAddr, netProto, rule)
	} else {
		pc, err := net.ListenPacket("udp", fmt.Sprintf(":%d", listenPort))
		if err != nil {
			m.mu.Unlock()
			return fmt.Errorf("relaywg: listen udp:%d failed: %w", listenPort, err)
		}
		stopFn = func() { _ = pc.Close() }
		go m.serveUDP(pc, fullAddr, netProto, rule)
	}

	m.rules[key] = &activeForward{rule: rule, close: stopFn}
	m.mu.Unlock()
	return nil
}

func (m *portForwardManager) remove(listenPort int, proto string) error {
	if m == nil {
		return nil
	}
	proto = strings.ToLower(strings.TrimSpace(proto))
	m.mu.Lock()
	defer m.mu.Unlock()
	if proto == "" || proto == "both" {
		for _, p := range []string{"tcp", "udp"} {
			if af, ok := m.rules[activeForwardKey{port: listenPort, proto: p}]; ok {
				af.close()
				delete(m.rules, activeForwardKey{port: listenPort, proto: p})
			}
		}
		return nil
	}
	if af, ok := m.rules[activeForwardKey{port: listenPort, proto: proto}]; ok {
		af.close()
		delete(m.rules, activeForwardKey{port: listenPort, proto: proto})
	}
	return nil
}

func (m *portForwardManager) closeAll() {
	if m == nil {
		return
	}
	m.mu.Lock()
	for k, af := range m.rules {
		af.close()
		delete(m.rules, k)
	}
	m.mu.Unlock()
}

func (m *portForwardManager) listJSON() string {
	if m == nil {
		return "[]"
	}
	m.mu.Lock()
	rules := make([]InboundForwardRule, 0, len(m.rules))
	for _, af := range m.rules {
		rules = append(rules, af.rule)
	}
	m.mu.Unlock()
	sort.Slice(rules, func(i, j int) bool {
		if rules[i].ListenPort != rules[j].ListenPort {
			return rules[i].ListenPort < rules[j].ListenPort
		}
		return rules[i].Proto < rules[j].Proto
	})
	raw, err := json.Marshal(rules)
	if err != nil {
		return "[]"
	}
	return string(raw)
}

func (m *portForwardManager) serveTCP(ln net.Listener, target tcpip.FullAddress, netProto tcpip.NetworkProtocolNumber, rule InboundForwardRule) {
	for {
		external, err := ln.Accept()
		if err != nil {
			return
		}
		go func(ext net.Conn) {
			if err := m.tun.limiter.checkQuota(); err != nil {
				_ = ext.Close()
				return
			}
			ctx, cancel := context.WithTimeout(m.tun.ctx, dialTimeout)
			tunConn, err := gonet.DialContextTCP(ctx, m.tun.stack, target, netProto)
			cancel()
			if err != nil {
				_ = ext.Close()
				return
			}
			label := fmt.Sprintf("in:%s:%d->%s:%d", rule.Proto, rule.ListenPort, rule.TargetIP, rule.TargetPort)
			entry := m.tun.flows.start("tcp-in", connClientLabel(ext), label)
			defer m.tun.flows.finish(entry)
			forwardTracked(tunConn, ext, tcpIdleTimeout, m.tun.ctx, m.tun.flows, m.tun.limiter, entry)
		}(external)
	}
}

func (m *portForwardManager) serveUDP(pc net.PacketConn, target tcpip.FullAddress, netProto tcpip.NetworkProtocolNumber, rule InboundForwardRule) {
	type udpPeer struct {
		conn     *gonet.UDPConn
		lastSeen time.Time
		entry    *flowEntry
	}
	var (
		mu    sync.Mutex
		peers = make(map[string]*udpPeer)
	)
	defer func() {
		mu.Lock()
		for _, p := range peers {
			_ = p.conn.Close()
		}
		mu.Unlock()
	}()

	buf := make([]byte, 64*1024)
	for {
		_ = pc.SetReadDeadline(time.Now().Add(15 * time.Second))
		n, remoteAddr, err := pc.ReadFrom(buf)
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				now := time.Now()
				mu.Lock()
				for k, p := range peers {
					if now.Sub(p.lastSeen) > udpIdleTimeout {
						_ = p.conn.Close()
						delete(peers, k)
					}
				}
				mu.Unlock()
				continue
			}
			return
		}
		if err := m.tun.limiter.consume(m.tun.ctx, dirDown, n); err != nil {
			continue
		}

		key := remoteAddr.String()
		mu.Lock()
		peer, ok := peers[key]
		if !ok {
			tunUDP, dialErr := gonet.DialUDP(m.tun.stack, nil, &target, netProto)
			if dialErr != nil {
				mu.Unlock()
				continue
			}
			label := fmt.Sprintf("in:%s:%d->%s:%d", rule.Proto, rule.ListenPort, rule.TargetIP, rule.TargetPort)
			clientHost := key
			if host, _, splitErr := net.SplitHostPort(key); splitErr == nil {
				clientHost = host
			}
			entry := m.tun.flows.start("udp-in", clientHost, label)
			peer = &udpPeer{conn: tunUDP, lastSeen: time.Now(), entry: entry}
			peers[key] = peer
			go func(k string, addr net.Addr, c *gonet.UDPConn, ent *flowEntry) {
				defer m.tun.flows.finish(ent)
				replyBuf := make([]byte, 64*1024)
				for {
					_ = c.SetReadDeadline(time.Now().Add(udpIdleTimeout))
					rn, rerr := c.Read(replyBuf)
					if rerr != nil {
						mu.Lock()
						delete(peers, k)
						mu.Unlock()
						_ = c.Close()
						return
					}
					if err := m.tun.limiter.consume(m.tun.ctx, dirUp, rn); err != nil {
						continue
					}
					m.tun.flows.addUp(ent, int64(rn))
					_, _ = pc.WriteTo(replyBuf[:rn], addr)
				}
			}(key, remoteAddr, tunUDP, entry)
		} else {
			peer.lastSeen = time.Now()
		}
		mu.Unlock()

		if wn, werr := peer.conn.Write(buf[:n]); werr == nil && wn > 0 {
			m.tun.flows.addDown(peer.entry, int64(wn))
		}
	}
}
