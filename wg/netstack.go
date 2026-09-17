package relaywg

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"strconv"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// The stack is built here rather than taken from wireguard-go's tun/netstack
// for one reason: that package can only dial and listen on addresses it owns,
// which is what a *client* needs. A forwarding endpoint has to accept traffic
// addressed to anywhere at all — the PC asks for 142.250.0.0:443, and nothing
// in this process holds that address.
//
// Owning the stack gives access to gVisor's TCP and UDP forwarders, which hand
// over connections for arbitrary destinations, and to promiscuous mode and
// spoofing, without which the stack drops those packets before a forwarder ever
// sees them.

const (
	nicID = tcpip.NICID(1)

	// A short window to get the outbound socket up. Longer and a dead
	// destination holds a tunnel connection open with nothing happening;
	// shorter and a slow-but-live host is called dead.
	dialTimeout = 10 * time.Second

	// Deep enough to ride out a scheduling hiccup, shallow enough not to become
	// a bufferbloat queue. It was 1024 -- about 1.4 MB of packets at this MTU --
	// and on a link that actually runs at a few Mbps that is over a second of
	// queue. Measured on hardware: tunnel latency went from 4 ms to 25 ms
	// average with spikes to 121 ms while a transfer was running, which is the
	// signature of exactly that.
	channelQueueDepth = 256

	// Matches conn.IdealBatchSize in wireguard-go. See [netTun.Read].
	batchSize = 128

	// Wire constants for [isEchoForElsewhere]. Written out rather than taken
	// from gVisor's header package: this is a byte-level filter on packets that
	// have not been parsed yet, and it runs before the stack sees them.
	ipv4MinimumHeader = 20
	ipv6HeaderLength  = 40
	protocolICMPv4    = 1
	protocolICMPv6    = 58
	icmpv4EchoRequest = 8
	icmpv6EchoRequest = 128
)

// Relay's own end of the tunnel as a number, so the per-packet filter compares
// four bytes instead of parsing a string. [isEchoForElsewhere] runs on every
// packet the peer sends.
var tunnelIPv4 = binary.BigEndian.Uint32(net.ParseIP(tunnelAddress).To4())

// netTun is a wireguard-go tun.Device backed by a gVisor stack we control.
//
// wireguard-go reads packets *from* here to encrypt and send to the peer, and
// writes decrypted packets *into* here. From the stack's point of view the
// directions are reversed, which is the one thing to keep straight while
// reading this file.
type netTun struct {
	stack    *stack.Stack
	endpoint *channel.Endpoint
	events   chan tun.Event
	mtu      int

	// Where forwarded traffic goes out. Nil means the phone's default route,
	// which is what every release before upstream proxying did and what this
	// still does unless someone configures otherwise. See upstream.go.
	out upstream

	// Cancelled by Close, so a Read blocked on an empty queue returns instead
	// of waiting for a packet that is never coming.
	ctx       context.Context
	cancel    context.CancelFunc
	closeOnce sync.Once
}

// dial opens the phone-side socket for one forwarded flow.
//
// Nil-safe on purpose: two benchmarks build a netTun directly and have no
// opinion about egress, and a zero value that behaves like the old code is
// worth more than three call sites that must remember to set a field.
func (d *netTun) dial(network, address string) (net.Conn, error) {
	if d.out == nil {
		return direct{}.Dial(network, address)
	}
	return d.out.Dial(network, address)
}

func newNetTun(mtu int) (*netTun, error) {
	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol, ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4, icmp.NewProtocol6,
		},
	})

	// TCP options, none of which gVisor turns on by default.
	//
	// SACK matters most here. Without it a single lost segment costs a full
	// retransmit-and-wait, and this traffic crosses Wi-Fi twice -- once to the
	// phone and once back out of it -- so loss is ordinary rather than rare.
	// Receive-buffer moderation lets a connection's window grow to the path
	// instead of sitting at the default forever.
	sack := tcpip.TCPSACKEnabled(true)
	if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack); err != nil {
		return nil, fmt.Errorf("relaywg: could not enable SACK: %v", err)
	}
	moderate := tcpip.TCPModerateReceiveBufferOption(true)
	if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate); err != nil {
		return nil, fmt.Errorf("relaywg: could not enable receive buffer moderation: %v", err)
	}
	sendRange := tcpip.TCPSendBufferSizeRangeOption{Min: 4 << 10, Default: 256 << 10, Max: 4 << 20}
	if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, &sendRange); err != nil {
		return nil, fmt.Errorf("relaywg: could not size the send buffer: %v", err)
	}
	recvRange := tcpip.TCPReceiveBufferSizeRangeOption{Min: 4 << 10, Default: 256 << 10, Max: 4 << 20}
	if err := s.SetTransportProtocolOption(tcp.ProtocolNumber, &recvRange); err != nil {
		return nil, fmt.Errorf("relaywg: could not size the receive buffer: %v", err)
	}

	endpoint := channel.New(channelQueueDepth, uint32(mtu), "")
	if err := s.CreateNIC(nicID, endpoint); err != nil {
		return nil, fmt.Errorf("relaywg: could not create the interface: %v", err)
	}

	// Promiscuous mode: accept frames that are not addressed to us — every
	// packet from the peer is addressed to somewhere on the internet.
	if err := s.SetPromiscuousMode(nicID, true); err != nil {
		return nil, fmt.Errorf("relaywg: promiscuous mode refused: %v", err)
	}
	// Spoofing: let sockets bind to addresses the stack does not own, which is
	// what a reply from 142.250.0.0 has to appear to come from.
	if err := s.SetSpoofing(nicID, true); err != nil {
		return nil, fmt.Errorf("relaywg: spoofing refused: %v", err)
	}

	// The tunnel's own address, so the peer can reach the endpoint itself --
	// a ping to it is the simplest "is the tunnel up?" a person can run.
	tunnelAddr := tcpip.AddrFromSlice(net.ParseIP(tunnelAddress).To4())
	protocolAddress := tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tunnelAddr.WithPrefix(),
	}
	if err := s.AddProtocolAddress(nicID, protocolAddress, stack.AddressProperties{}); err != nil {
		return nil, fmt.Errorf("relaywg: could not assign the tunnel address: %v", err)
	}

	// Everything, both families, goes to this NIC. There is nowhere else.
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	ctx, cancel := context.WithCancel(context.Background())
	device := &netTun{
		stack:    s,
		endpoint: endpoint,
		events:   make(chan tun.Event, 2),
		mtu:      mtu,
		ctx:      ctx,
		cancel:   cancel,
	}
	device.events <- tun.EventUp

	return device, nil
}

// copyPacket flattens one packet into [into] and always releases it.
//
// Deliberately not packet.ToView(), which is what this used to call. ToView
// takes a *View and a chunk from gVisor's own pools and hands back a copy --
// and nothing here ever called Release on it, so every packet the tunnel
// carried in either direction leaked two pooled objects. The pools then never
// recycled anything and the garbage collector chased the difference, on a phone,
// on the single CPU that measurement keeps identifying as the limit.
//
// AsSlices borrows the packet's own storage instead: one copy into the caller's
// buffer, nothing taken from a pool, nothing to give back.
//
// Reports false rather than truncating. A packet larger than the buffer cannot
// happen at this MTU, and if it ever did, half a packet is worse than none.
func copyPacket(packet *stack.PacketBuffer, into []byte) (int, bool) {
	defer packet.DecRef()

	written := 0
	for _, slice := range packet.AsSlices() {
		if written+len(slice) > len(into) {
			return 0, false
		}
		written += copy(into[written:], slice)
	}
	return written, true
}

// Read fills as many of [bufs] as there are packets waiting.
//
// It used to return exactly one packet per call, with BatchSize reporting 1.
// That is the single most expensive line in the forwarder: wireguard-go is
// built around batches of up to conn.IdealBatchSize, and hands each batch to a
// crypto worker as a unit. A batch of one pays the whole per-batch cost -- the
// queue hand-off, the worker wake-up, the nonce and ring bookkeeping -- for
// every single packet, and at 1420 bytes a packet that is the entire budget of
// a weak phone.
//
// So: block for the first packet, then take everything else already queued
// without blocking. Under load a call returns a full batch; when idle it
// behaves exactly as before.
//
// It now reads the endpoint directly. There used to be a goroutine draining the
// endpoint into a second channel of the same depth, which bought nothing and
// cost two things: a scheduler hand-off for every packet on the hottest path in
// the program, and a second queue -- so the buffering that was deliberately cut
// to 256 packets to stop the tunnel bloating to 121 ms under load was in fact
// still 512.
func (d *netTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if len(bufs) == 0 {
		return 0, nil
	}

	count := 0
	// The first one blocks: returning zero packets would spin the caller.
	for count == 0 {
		packet := d.endpoint.ReadContext(d.ctx)
		if packet == nil {
			return 0, net.ErrClosed
		}
		n, ok := copyPacket(packet, bufs[0][offset:])
		if !ok {
			continue // oversized; drop it and wait for a real one
		}
		sizes[0] = n
		count = 1
	}

	// Then everything already queued, without blocking.
	for count < len(bufs) {
		packet := d.endpoint.Read()
		if packet == nil {
			break
		}
		n, ok := copyPacket(packet, bufs[count][offset:])
		if !ok {
			continue
		}
		sizes[count] = n
		count++
	}
	return count, nil
}

func (d *netTun) Write(bufs [][]byte, offset int) (int, error) {
	written := 0
	for _, buf := range bufs {
		packet := buf[offset:]
		if len(packet) == 0 {
			continue
		}
		// The IP version lives in the top nibble of the first byte. A packet
		// injected under the wrong protocol number is dropped silently, which
		// looks exactly like a network that does not work.
		var protocol tcpip.NetworkProtocolNumber
		switch packet[0] >> 4 {
		case 4:
			protocol = header.IPv4ProtocolNumber
		case 6:
			protocol = header.IPv6ProtocolNumber
		default:
			continue // not IP; nothing sensible to do with it
		}

		// A ping for somewhere Relay does not carry traffic to.
		//
		// The stack has to be promiscuous, or the TCP and UDP forwarders would
		// never see a packet addressed to the internet -- and that same setting
		// makes gVisor treat an echo request for *any* address as locally
		// destined and answer it. So `ping 1.1.1.1` through Relay always
		// succeeded, from the netstack, without a byte leaving the phone.
		//
		// That is the first command anybody runs, and it could not fail. A user
		// reported both ends Connected, ping answering in 50 ms and nothing
		// else working at all; the tells were TTL=64, one hop away, and a
		// traceroute that finished in a single hop because the responder
		// ignored TTL. An answer that cannot fail is worth less than no answer.
		//
		// Dropped rather than forwarded: there is no unprivileged way to send
		// ICMP from an Android app, and SOCKS5 -- the upstream in upstream.go --
		// cannot carry it at all, so forwarding would work in one configuration
		// and not the other. Not counted as written, like the non-IP case above.
		if isEchoForElsewhere(packet) {
			continue
		}

		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(packet),
		})
		d.endpoint.InjectInbound(protocol, pkt)
		pkt.DecRef()
		written++
	}
	return written, nil
}

// isEchoForElsewhere reports whether a packet is an ICMP echo request for an
// address that is not Relay's own end of the tunnel.
//
// Echo requests only. An echo *reply* arriving from the peer is already dropped
// by the stack, and the other ICMP types are diagnostics gVisor does not
// synthesise. Narrow on purpose: this is a filter on the hottest path in the
// program, and every packet that is not a ping has to leave it untouched.
//
// [tunnelAddress] stays answerable. It is the one address a reply is the truth
// about -- the peer really is talking to it -- and the Windows client pings
// exactly it to show tunnel latency (TunnelStats.PingPeer), so a blanket refusal
// would leave that reading blank forever.
func isEchoForElsewhere(packet []byte) bool {
	switch packet[0] >> 4 {
	case 4:
		if len(packet) < ipv4MinimumHeader {
			return false
		}
		headerLength := int(packet[0]&0x0f) * 4
		if headerLength < ipv4MinimumHeader || len(packet) < headerLength+1 {
			return false
		}
		if packet[9] != protocolICMPv4 || packet[headerLength] != icmpv4EchoRequest {
			return false
		}
		// A fragment other than the first carries no ICMP header to read, and
		// the bytes at headerLength are payload. Fragment offset is the low 13
		// bits of the flags word.
		if binary.BigEndian.Uint16(packet[6:8])&0x1FFF != 0 {
			return false
		}
		return binary.BigEndian.Uint32(packet[16:20]) != tunnelIPv4

	case 6:
		// The tunnel is IPv4-only -- the Windows client refuses IPv6 outright
		// because nothing here carries it -- so the stack owns no IPv6 address
		// and every echo request is for somewhere else. Extension headers are
		// not walked: with any present, the next-header byte is not ICMPv6 and
		// this returns false, which is the safe direction.
		if len(packet) < ipv6HeaderLength+1 {
			return false
		}
		return packet[6] == protocolICMPv6 && packet[ipv6HeaderLength] == icmpv6EchoRequest
	}
	return false
}

func (d *netTun) Flush() error             { return nil }
func (d *netTun) MTU() (int, error)        { return d.mtu, nil }
func (d *netTun) Name() (string, error)    { return "relay0", nil }
func (d *netTun) File() *os.File           { return nil }
func (d *netTun) Events() <-chan tun.Event { return d.events }

// BatchSize is what wireguard-go sizes its buffers and crypto batches by.
// It must match what Read is actually willing to fill.
func (d *netTun) BatchSize() int { return batchSize }

func (d *netTun) Close() error {
	d.closeOnce.Do(func() {
		d.cancel()
		d.endpoint.Close()
		d.stack.Close()
		close(d.events)
	})
	return nil
}

// installForwarders is what makes this an exit node rather than a client.
//
// Both forwarders receive connections addressed to anywhere, open the matching
// real socket on the phone, and splice the two. Those real sockets are ordinary
// Go sockets, so they take the phone's default route — through its VPN when one
// is up, which is the entire point of the feature.
func (d *netTun) installForwarders() {
	tcpForwarder := tcp.NewForwarder(d.stack, 0, 512, func(req *tcp.ForwarderRequest) {
		id := req.ID()
		destination := net.JoinHostPort(
			addrToString(id.LocalAddress), strconv.Itoa(int(id.LocalPort)))

		outbound, err := d.dial("tcp", destination)
		if err != nil {
			// Refuse rather than drop: the peer learns immediately instead of
			// waiting out its own connect timeout on a host that is not there.
			req.Complete(true)
			return
		}

		var wq waiter.Queue
		ep, tcpErr := req.CreateEndpoint(&wq)
		if tcpErr != nil {
			outbound.Close()
			req.Complete(true)
			return
		}
		req.Complete(false)

		go forward(gonet.NewTCPConn(&wq, ep), outbound, tcpIdleTimeout)
	})
	d.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(d.stack, func(req *udp.ForwarderRequest) {
		id := req.ID()
		destination := net.JoinHostPort(
			addrToString(id.LocalAddress), strconv.Itoa(int(id.LocalPort)))

		outbound, err := d.dial("udp", destination)
		if err != nil {
			return // UDP has nothing to refuse with
		}

		var wq waiter.Queue
		ep, udpErr := req.CreateEndpoint(&wq)
		if udpErr != nil {
			outbound.Close()
			return
		}

		go forward(gonet.NewUDPConn(d.stack, &wq, ep), outbound, udpIdleTimeout)
	})
	d.stack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

func addrToString(addr tcpip.Address) string {
	return net.IP(addr.AsSlice()).String()
}
