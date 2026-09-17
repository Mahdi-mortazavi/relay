package relaywg

import (
	"encoding/binary"
	"net"
	"testing"
	"time"
)

// What Relay does when the PC pings something.
//
// This is the test that exists because of a user's report. Their phone and
// laptop both said Connected, `ping 1.1.1.1` answered in 50 ms, and nothing
// else worked -- so the first thing anyone reaches for said the internet was
// fine while nothing was crossing at all. Two things in their output give it
// away: the replies came back with **TTL=64**, meaning the responder was one
// hop away rather than somewhere across the world, and `tracert 1.1.1.1`
// completed in a **single hop**, meaning whatever answered ignored TTL.
//
// The cause is in newNetTun. The stack registers icmp.NewProtocol4 and is put
// in promiscuous mode -- it has to be, or the TCP and UDP forwarders would
// never see packets addressed to the internet -- so gVisor treats an echo
// request for any address as locally destined and answers it. Relay forwards
// TCP and UDP and nothing else, so every one of those replies is a claim about
// a path that was never tried.
//
// Packets here are assembled from raw bytes rather than gVisor's header
// package: this file has to be exact about what goes on the wire, and the wire
// format is fixed.

// Written out again rather than taken from netstack.go's own copies. A test
// that builds its packets from the same constants the filter reads them with
// cannot notice one of those constants being wrong: both sides would move
// together and the test would still pass. These are the values from RFC 791
// and RFC 792, independently.
const (
	protoICMP        = 1
	icmpEchoRequest  = 8
	icmpEchoReply    = 0
	ipv4HeaderLength = 20
)

// echoRequest builds one IPv4 ICMP echo request, checksums and all.
func echoRequest(source, destination string, identifier uint16) []byte {
	const payload = 16
	total := ipv4HeaderLength + 8 + payload
	packet := make([]byte, total)

	packet[0] = 0x45 // IPv4, 5 words of header
	binary.BigEndian.PutUint16(packet[2:4], uint16(total))
	packet[8] = 64 // TTL
	packet[9] = protoICMP
	copy(packet[12:16], net.ParseIP(source).To4())
	copy(packet[16:20], net.ParseIP(destination).To4())
	binary.BigEndian.PutUint16(packet[10:12], onesComplement(packet[:ipv4HeaderLength]))

	echo := packet[ipv4HeaderLength:]
	echo[0] = icmpEchoRequest
	binary.BigEndian.PutUint16(echo[4:6], identifier)
	binary.BigEndian.PutUint16(echo[6:8], 1) // sequence
	for i := range echo[8:] {
		echo[8+i] = byte(i)
	}
	binary.BigEndian.PutUint16(echo[2:4], onesComplement(echo))

	return packet
}

// onesComplement is the internet checksum of RFC 1071.
func onesComplement(data []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(data); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for sum > 0xFFFF {
		sum = (sum >> 16) + (sum & 0xFFFF)
	}
	return ^uint16(sum)
}

// waitForPacket returns the next packet the stack emits, or nil.
//
// Not netTun.Read: that blocks on its context until the device closes, which
// is the right thing for the forwarder and a hang for a test whose point is
// that nothing comes back.
func waitForPacket(device *netTun, within time.Duration) []byte {
	deadline := time.Now().Add(within)
	into := make([]byte, 1500)
	for time.Now().Before(deadline) {
		if packet := device.endpoint.Read(); packet != nil {
			if n, ok := copyPacket(packet, into); ok {
				return into[:n]
			}
			continue
		}
		time.Sleep(2 * time.Millisecond)
	}
	return nil
}

// isEchoReply reports whether this is an ICMP echo reply, and from where.
func isEchoReply(packet []byte) (bool, string) {
	if len(packet) < ipv4HeaderLength+8 || packet[0]>>4 != 4 || packet[9] != protoICMP {
		return false, ""
	}
	if packet[ipv4HeaderLength] != icmpEchoReply {
		return false, ""
	}
	return true, net.IP(packet[12:16]).String()
}

func TestRelayDoesNotAnswerPingsForAddressesItCannotCarry(t *testing.T) {
	// The whole point. Relay carries TCP and UDP; it does not carry ICMP to
	// anywhere. Answering anyway turns the first command anybody runs into a
	// guarantee that cannot fail, which is worse than no answer -- it sent a
	// real user's troubleshooting down the wrong path for an evening.
	device, err := newNetTun(mtu)
	if err != nil {
		t.Fatalf("newNetTun: %v", err)
	}
	defer device.Close()

	for _, destination := range []string{"1.1.1.1", "8.8.8.8", "203.0.113.9"} {
		if _, err := device.Write([][]byte{echoRequest("10.13.37.2", destination, 0x4242)}, 0); err != nil {
			t.Fatalf("write: %v", err)
		}
		if answer := waitForPacket(device, 400*time.Millisecond); answer != nil {
			if reply, from := isEchoReply(answer); reply {
				t.Fatalf("Relay answered a ping for %s, claiming to be %s. "+
					"It forwards no ICMP anywhere, so that reply is a fact about "+
					"a path nothing ever tried.", destination, from)
			}
			t.Fatalf("something came back for %s: % x", destination, answer)
		}
	}
}

func TestTheFilterDoesNotEatRealTraffic(t *testing.T) {
	// The dangerous failure mode of the change above. A byte-level sniff on
	// the hottest path in the program, one nibble away from dropping every
	// packet the product exists to carry -- so: a TCP packet, a UDP packet and
	// an ICMP fragment that carries no readable header all have to go through.
	device, err := newNetTun(mtu)
	if err != nil {
		t.Fatalf("newNetTun: %v", err)
	}
	defer device.Close()

	cases := []struct {
		what   string
		packet []byte
	}{
		{"a TCP SYN to a public address", transportPacket(6, "1.1.1.1")},
		{"a UDP datagram to a public address", transportPacket(17, "8.8.8.8")},
		{"an ICMP echo to the tunnel itself", echoRequest("10.13.37.2", tunnelAddress, 1)},
		{"a later ICMP fragment, which has no header to read", laterFragment()},
	}
	for _, c := range cases {
		n, err := device.Write([][]byte{c.packet}, 0)
		if err != nil {
			t.Fatalf("%s: write: %v", c.what, err)
		}
		if n != 1 {
			t.Fatalf("%s was dropped by the ping filter", c.what)
		}
	}
}

// transportPacket is an IPv4 header carrying eight bytes of nothing under the
// given protocol number. Enough to be accepted or dropped; not a valid segment.
func transportPacket(protocol byte, destination string) []byte {
	total := ipv4HeaderLength + 8
	packet := make([]byte, total)
	packet[0] = 0x45
	binary.BigEndian.PutUint16(packet[2:4], uint16(total))
	packet[8] = 64
	packet[9] = protocol
	copy(packet[12:16], net.ParseIP("10.13.37.2").To4())
	copy(packet[16:20], net.ParseIP(destination).To4())
	binary.BigEndian.PutUint16(packet[10:12], onesComplement(packet[:ipv4HeaderLength]))
	return packet
}

// laterFragment is an ICMP packet with a non-zero fragment offset, so the byte
// at the end of the header is payload rather than an ICMP type. Reading it as
// a type is how a bounds-and-flags mistake would show up.
func laterFragment() []byte {
	packet := transportPacket(protoICMP, "1.1.1.1")
	binary.BigEndian.PutUint16(packet[6:8], 185) // offset 185*8 bytes in
	packet[ipv4HeaderLength] = icmpEchoRequest   // looks like a ping, is not
	binary.BigEndian.PutUint16(packet[10:12], 0)
	binary.BigEndian.PutUint16(packet[10:12], onesComplement(packet[:ipv4HeaderLength]))
	return packet
}

func TestPingingTheTunnelItselfStillAnswers(t *testing.T) {
	// The other half, and the reason this is a filter rather than dropping
	// ICMP from the stack altogether. 10.13.37.1 is Relay's own end of the
	// tunnel, it is the one address a reply is the truth about, and the Windows
	// client pings exactly it once every five ticks to show tunnel latency --
	// see TunnelStats.PingPeer. Removing ICMP would leave that reading blank
	// forever.
	device, err := newNetTun(mtu)
	if err != nil {
		t.Fatalf("newNetTun: %v", err)
	}
	defer device.Close()

	if _, err := device.Write([][]byte{echoRequest("10.13.37.2", tunnelAddress, 0x4242)}, 0); err != nil {
		t.Fatalf("write: %v", err)
	}
	answer := waitForPacket(device, 2*time.Second)
	if answer == nil {
		t.Fatal("pinging the tunnel's own address got no answer; the latency reading depends on it")
	}
	reply, from := isEchoReply(answer)
	if !reply {
		t.Fatalf("got something that is not an echo reply: % x", answer)
	}
	if from != tunnelAddress {
		t.Fatalf("the reply came from %s, not %s", from, tunnelAddress)
	}
}
