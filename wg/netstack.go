package relaywg

import (
	"context"
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

	// gVisor's own recommended queue depth for a channel endpoint.
	channelQueueDepth = 1024
)

// netTun is a wireguard-go tun.Device backed by a gVisor stack we control.
//
// wireguard-go reads packets *from* here to encrypt and send to the peer, and
// writes decrypted packets *into* here. From the stack's point of view the
// directions are reversed, which is the one thing to keep straight while
// reading this file.
type netTun struct {
	stack    *stack.Stack
	endpoint *channel.Endpoint
	incoming chan *stack.PacketBuffer
	events   chan tun.Event
	mtu      int

	closeOnce sync.Once
	closed    chan struct{}
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

	device := &netTun{
		stack:    s,
		endpoint: endpoint,
		incoming: make(chan *stack.PacketBuffer, channelQueueDepth),
		events:   make(chan tun.Event, 2),
		mtu:      mtu,
		closed:   make(chan struct{}),
	}
	device.events <- tun.EventUp

	go device.pumpOutbound()
	return device, nil
}

// pumpOutbound moves packets the stack wants to send into the queue wireguard-go
// reads from. channel.Endpoint hands them over one at a time and blocks, so it
// needs a goroutine of its own.
func (d *netTun) pumpOutbound() {
	for {
		packet := d.endpoint.ReadContext(context.Background())
		if packet == nil {
			return // endpoint closed
		}
		select {
		case d.incoming <- packet:
		case <-d.closed:
			packet.DecRef()
			return
		}
	}
}

func (d *netTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	select {
	case <-d.closed:
		return 0, net.ErrClosed
	case packet := <-d.incoming:
		defer packet.DecRef()
		view := packet.ToView()
		n, err := view.Read(bufs[0][offset:])
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		return 1, nil
	}
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

		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(packet),
		})
		d.endpoint.InjectInbound(protocol, pkt)
		pkt.DecRef()
		written++
	}
	return written, nil
}

func (d *netTun) Flush() error           { return nil }
func (d *netTun) MTU() (int, error)      { return d.mtu, nil }
func (d *netTun) Name() (string, error)  { return "relay0", nil }
func (d *netTun) File() *os.File         { return nil }
func (d *netTun) Events() <-chan tun.Event { return d.events }
func (d *netTun) BatchSize() int         { return 1 }

func (d *netTun) Close() error {
	d.closeOnce.Do(func() {
		close(d.closed)
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

		outbound, err := net.DialTimeout("tcp", destination, dialTimeout)
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

		go forward(gonet.NewTCPConn(&wq, ep), outbound)
	})
	d.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(d.stack, func(req *udp.ForwarderRequest) {
		id := req.ID()
		destination := net.JoinHostPort(
			addrToString(id.LocalAddress), strconv.Itoa(int(id.LocalPort)))

		outbound, err := net.DialTimeout("udp", destination, dialTimeout)
		if err != nil {
			return // UDP has nothing to refuse with
		}

		var wq waiter.Queue
		ep, udpErr := req.CreateEndpoint(&wq)
		if udpErr != nil {
			outbound.Close()
			return
		}

		go forward(gonet.NewUDPConn(d.stack, &wq, ep), outbound)
	})
	d.stack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

func addrToString(addr tcpip.Address) string {
	return net.IP(addr.AsSlice()).String()
}
