package relaywg

import (
	"encoding/binary"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/icmp"
	"golang.org/x/net/ipv4"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type icmpPendingKey struct {
	seq uint16
	dst [4]byte
}

type icmpPending struct {
	srcIP   [4]byte
	origID  uint16
	created time.Time
}

// icmpRelay forwards ICMPv4 echo requests to real destinations using an
// unprivileged datagram ICMP socket ("udp4" on Android/Linux) when direct
// egress is active. If the OS refuses ICMP sockets or an upstream SOCKS5 proxy
// is active (which cannot carry ICMP without bypassing the VPN), it stays
// inactive so external pings drop honestly rather than spoofing replies.
type icmpRelay struct {
	tun     *netTun
	enabled atomic.Bool
	mu      sync.Mutex
	conn    *icmp.PacketConn
	pending map[icmpPendingKey]icmpPending
	closed  atomic.Bool
}

func newICMPRelay(tun *netTun) *icmpRelay {
	return &icmpRelay{
		tun:     tun,
		pending: make(map[icmpPendingKey]icmpPending),
	}
}

// startIfPermitted tries to open an unprivileged ICMPv4 socket. Failing here is
// expected on OSes/sandboxes that disallow raw/dgram ICMP and is non-fatal.
func (r *icmpRelay) startIfPermitted() bool {
	if r == nil || r.closed.Load() {
		return false
	}
	r.mu.Lock()
	if r.conn != nil {
		r.enabled.Store(true)
		r.mu.Unlock()
		return true
	}
	r.mu.Unlock()

	pc, err := icmp.ListenPacket("udp4", "0.0.0.0")
	if err != nil {
		pc, err = icmp.ListenPacket("ip4:icmp", "0.0.0.0")
		if err != nil {
			r.enabled.Store(false)
			return false
		}
	}

	r.mu.Lock()
	if r.closed.Load() || r.conn != nil {
		r.mu.Unlock()
		pc.Close()
		return r.conn != nil
	}
	r.conn = pc
	r.enabled.Store(true)
	r.mu.Unlock()

	go r.readLoop(pc)
	return true
}

func (r *icmpRelay) setEnabled(on bool) {
	if r == nil {
		return
	}
	if !on {
		r.enabled.Store(false)
		return
	}
	r.startIfPermitted()
}

func (r *icmpRelay) active() bool {
	return r != nil && !r.closed.Load() && r.enabled.Load()
}

func (r *icmpRelay) close() {
	if r == nil {
		return
	}
	r.closed.Store(true)
	r.enabled.Store(false)
	r.mu.Lock()
	pc := r.conn
	r.conn = nil
	r.mu.Unlock()
	if pc != nil {
		_ = pc.Close()
	}
}

func (r *icmpRelay) forwardEchoV4(ipPacket []byte) bool {
	if !r.active() || len(ipPacket) < ipv4MinimumHeader+8 {
		return false
	}
	ihl := int(ipPacket[0]&0x0f) * 4
	if ihl < ipv4MinimumHeader || len(ipPacket) < ihl+8 {
		return false
	}
	icmpBytes := ipPacket[ihl:]
	if icmpBytes[0] != icmpv4EchoRequest {
		return false
	}
	origID := binary.BigEndian.Uint16(icmpBytes[4:6])
	seq := binary.BigEndian.Uint16(icmpBytes[6:8])

	var srcIP, dstIP [4]byte
	copy(srcIP[:], ipPacket[12:16])
	copy(dstIP[:], ipPacket[16:20])

	r.mu.Lock()
	pc := r.conn
	if pc == nil {
		r.mu.Unlock()
		return false
	}
	now := time.Now()
	if len(r.pending) > 256 {
		for k, v := range r.pending {
			if now.Sub(v.created) > 10*time.Second {
				delete(r.pending, k)
			}
		}
	}
	r.pending[icmpPendingKey{seq: seq, dst: dstIP}] = icmpPending{
		srcIP:   srcIP,
		origID:  origID,
		created: now,
	}
	r.mu.Unlock()

	msg := icmp.Message{
		Type: ipv4.ICMPTypeEcho,
		Code: 0,
		Body: &icmp.Echo{
			ID:   int(origID),
			Seq:  int(seq),
			Data: icmpBytes[8:],
		},
	}
	wire, err := msg.Marshal(nil)
	if err != nil {
		return false
	}
	var dstAddr net.Addr
	if _, ok := pc.LocalAddr().(*net.UDPAddr); ok {
		dstAddr = &net.UDPAddr{IP: net.IPv4(dstIP[0], dstIP[1], dstIP[2], dstIP[3])}
	} else {
		dstAddr = &net.IPAddr{IP: net.IPv4(dstIP[0], dstIP[1], dstIP[2], dstIP[3])}
	}
	_, err = pc.WriteTo(wire, dstAddr)
	return err == nil
}

func (r *icmpRelay) readLoop(pc *icmp.PacketConn) {
	buf := make([]byte, 2048)
	for !r.closed.Load() {
		n, peerAddr, err := pc.ReadFrom(buf)
		if err != nil {
			if r.closed.Load() {
				return
			}
			continue
		}
		parsed, err := icmp.ParseMessage(1, buf[:n])
		if err != nil || parsed.Type != ipv4.ICMPTypeEchoReply {
			continue
		}
		echo, ok := parsed.Body.(*icmp.Echo)
		if !ok {
			continue
		}
		peerIP := addrToIPv4Bytes(peerAddr)
		if peerIP == nil {
			continue
		}
		var dstKey [4]byte
		copy(dstKey[:], peerIP)
		key := icmpPendingKey{seq: uint16(echo.Seq), dst: dstKey}

		r.mu.Lock()
		entry, found := r.pending[key]
		if found {
			delete(r.pending, key)
		}
		r.mu.Unlock()
		if !found {
			continue
		}

		replyPkt := buildIPv4EchoReply(dstKey, entry.srcIP, entry.origID, uint16(echo.Seq), echo.Data)
		if r.tun == nil || r.tun.endpoint == nil {
			continue
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(replyPkt),
		})
		r.tun.endpoint.InjectInbound(header.IPv4ProtocolNumber, pkt)
		pkt.DecRef()
	}
}

func addrToIPv4Bytes(a net.Addr) net.IP {
	switch v := a.(type) {
	case *net.UDPAddr:
		return v.IP.To4()
	case *net.IPAddr:
		return v.IP.To4()
	default:
		return nil
	}
}

func buildIPv4EchoReply(src, dst [4]byte, id, seq uint16, payload []byte) []byte {
	totalLen := 20 + 8 + len(payload)
	pkt := make([]byte, totalLen)
	pkt[0] = 0x45 // version 4, IHL 5 (20 bytes)
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	pkt[8] = 64 // TTL
	pkt[9] = protocolICMPv4
	copy(pkt[12:16], src[:])
	copy(pkt[16:20], dst[:])
	binary.BigEndian.PutUint16(pkt[10:12], inetChecksum(pkt[:20]))

	icmpBody := pkt[20:]
	icmpBody[0] = 0 // Echo Reply
	icmpBody[1] = 0
	binary.BigEndian.PutUint16(icmpBody[4:6], id)
	binary.BigEndian.PutUint16(icmpBody[6:8], seq)
	copy(icmpBody[8:], payload)
	binary.BigEndian.PutUint16(icmpBody[2:4], inetChecksum(icmpBody))
	return pkt
}

func inetChecksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum > 0xffff {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}
