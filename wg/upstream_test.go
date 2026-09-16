package relaywg

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

// A SOCKS5 proxy small enough to read, so the client above is asserted against
// the protocol rather than against itself.
//
// It speaks the parts RFC 1928 requires of a server Relay would meet on a
// phone: no authentication, CONNECT, and UDP ASSOCIATE. Everything else it
// refuses, which is how the client's error paths get exercised.
type fakeSocks struct {
	listener net.Listener
	// echoed back to whoever connects through CONNECT
	connectTo string
	// set by the server when a CONNECT request arrives, so a test can assert
	// the client asked for the destination it was given.
	lastRequest chan string
}

func newFakeSocks(t *testing.T) *fakeSocks {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("could not listen: %v", err)
	}
	s := &fakeSocks{listener: listener, lastRequest: make(chan string, 4)}
	go s.serve()
	t.Cleanup(func() { listener.Close() })
	return s
}

func (s *fakeSocks) address() string { return s.listener.Addr().String() }

func (s *fakeSocks) serve() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		go s.handle(conn)
	}
}

func (s *fakeSocks) handle(conn net.Conn) {
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(conn, greeting); err != nil {
		conn.Close()
		return
	}
	methods := make([]byte, greeting[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		conn.Close()
		return
	}
	conn.Write([]byte{socksVersion, socksNoAuth})

	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		conn.Close()
		return
	}
	host, port, err := readAddressFrom(conn, head[3])
	if err != nil {
		conn.Close()
		return
	}
	select {
	case s.lastRequest <- net.JoinHostPort(host, strconv.Itoa(port)):
	default:
	}

	switch head[1] {
	case cmdConnect:
		// Reply, then become an echo so the test can prove the same socket
		// carries the payload afterwards.
		conn.Write(replyBytes("127.0.0.1", 0))
		io.Copy(conn, conn)
		conn.Close()
	case cmdUDPAssoc:
		relay, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			conn.Close()
			return
		}
		_, portText, _ := net.SplitHostPort(relay.LocalAddr().String())
		relayPort, _ := strconv.Atoi(portText)
		conn.Write(replyBytes("127.0.0.1", relayPort))
		go echoDatagrams(relay)
		// Hold the control connection: closing it is how the client signals
		// the association is over, and this must not end first.
		io.Copy(io.Discard, conn)
		relay.Close()
		conn.Close()
	default:
		conn.Write([]byte{socksVersion, 0x07, 0x00, atypIPv4, 0, 0, 0, 0, 0, 0})
		conn.Close()
	}
}

// echoDatagrams sends every datagram back with its header intact, which is what
// a real proxy does when the far side answers.
func echoDatagrams(relay net.PacketConn) {
	buffer := make([]byte, 2048)
	for {
		n, from, err := relay.ReadFrom(buffer)
		if err != nil {
			return
		}
		relay.WriteTo(buffer[:n], from)
	}
}

func readAddressFrom(r io.Reader, atyp byte) (string, int, error) {
	var host string
	switch atyp {
	case atypIPv4:
		buf := make([]byte, 4)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", 0, err
		}
		host = net.IP(buf).String()
	case atypIPv6:
		buf := make([]byte, 16)
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", 0, err
		}
		host = net.IP(buf).String()
	case atypDomainName:
		length := make([]byte, 1)
		if _, err := io.ReadFull(r, length); err != nil {
			return "", 0, err
		}
		buf := make([]byte, length[0])
		if _, err := io.ReadFull(r, buf); err != nil {
			return "", 0, err
		}
		host = string(buf)
	default:
		return "", 0, io.ErrUnexpectedEOF
	}
	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(r, portBytes); err != nil {
		return "", 0, err
	}
	return host, int(binary.BigEndian.Uint16(portBytes)), nil
}

func replyBytes(host string, port int) []byte {
	out := []byte{socksVersion, replySucceeded, 0x00, atypIPv4}
	out = append(out, net.ParseIP(host).To4()...)
	return binary.BigEndian.AppendUint16(out, uint16(port))
}

// --- the tests -------------------------------------------------------------

func TestNoProxyKeepsTheBehaviourEveryReleaseHasHad(t *testing.T) {
	// The default has to stay a plain socket on the phone's default route. A
	// change here would reroute every existing user's traffic without anyone
	// asking for it.
	out, err := newUpstream("")
	if err != nil {
		t.Fatalf("empty upstream should be fine: %v", err)
	}
	if _, isDirect := out.(direct); !isDirect {
		t.Fatalf("empty upstream became %T, not direct", out)
	}
}

func TestANetTunWithNoUpstreamStillDials(t *testing.T) {
	// The nil-safety that lets the benchmarks build a netTun without caring.
	// Without it they panic, and the panic is in a goroutine handling a packet
	// where nobody would look for it.
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()

	device := &netTun{}
	conn, err := device.dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatalf("a netTun with no upstream could not dial: %v", err)
	}
	conn.Close()
}

func TestTcpGoesThroughTheProxyAndCarriesPayload(t *testing.T) {
	proxy := newFakeSocks(t)
	out, err := newUpstream(proxy.address())
	if err != nil {
		t.Fatalf("newUpstream: %v", err)
	}

	conn, err := out.Dial("tcp", "203.0.113.7:443")
	if err != nil {
		t.Fatalf("dial through proxy: %v", err)
	}
	defer conn.Close()

	// The proxy was asked for the destination the caller named, not for the
	// proxy's own address — the mistake that would make everything appear to
	// work while every connection went to the wrong place.
	select {
	case asked := <-proxy.lastRequest:
		if asked != "203.0.113.7:443" {
			t.Fatalf("proxy was asked for %q", asked)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("the proxy never saw a request")
	}

	// And the same socket carries the payload afterwards.
	payload := []byte("relay carries this")
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("write: %v", err)
	}
	echo := make([]byte, len(payload))
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := io.ReadFull(conn, echo); err != nil {
		t.Fatalf("read back: %v", err)
	}
	if !bytes.Equal(echo, payload) {
		t.Fatalf("got %q back", echo)
	}
}

func TestUdpGoesThroughTheProxyToo(t *testing.T) {
	// The reason this client is hand-written. Relay's claim is that it carries
	// TCP *and* UDP, so a proxy mode that dropped every datagram would quietly
	// turn the product into a browser tunnel — calls and games gone, with
	// nothing on screen to say so.
	proxy := newFakeSocks(t)
	out, err := newUpstream(proxy.address())
	if err != nil {
		t.Fatalf("newUpstream: %v", err)
	}

	conn, err := out.Dial("udp", "203.0.113.9:53")
	if err != nil {
		t.Fatalf("udp associate: %v", err)
	}
	defer conn.Close()

	payload := []byte("a datagram")
	n, err := conn.Write(payload)
	if err != nil {
		t.Fatalf("write: %v", err)
	}
	// Reporting the wrapped length would make io.Copy think it wrote more than
	// it was handed, which surfaces much later as a short-write error.
	if n != len(payload) {
		t.Fatalf("Write reported %d for a %d-byte payload", n, len(payload))
	}

	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	back := make([]byte, 2048)
	read, err := conn.Read(back)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(back[:read], payload) {
		t.Fatalf("got %q back, header not stripped?", back[:read])
	}
}

func TestAMalformedUpstreamAddressIsRefusedBeforeAnythingStarts(t *testing.T) {
	// Better here than as a tunnel that comes up and quietly forwards nothing.
	for _, bad := range []string{"127.0.0.1", "127.0.0.1:0", "127.0.0.1:99999", "nonsense"} {
		if _, err := newUpstream(bad); err == nil {
			t.Fatalf("%q was accepted as an upstream", bad)
		}
	}
}

func TestAPortOnItsOwnMeansThisPhone(t *testing.T) {
	// A VPN client's proxy is always on loopback, and ":10808" is what a person
	// would type. Defaulting the host beats refusing them over a missing "127.0.0.1".
	out, err := newUpstream(":10808")
	if err != nil {
		t.Fatalf("a bare port should be accepted: %v", err)
	}
	proxy, ok := out.(socks5)
	if !ok {
		t.Fatalf("got %T", out)
	}
	if proxy.address != "127.0.0.1:10808" {
		t.Fatalf("bare port resolved to %q", proxy.address)
	}
}

func TestADatagramHeaderIsStrippedExactly(t *testing.T) {
	// Off-by-one here does not fail loudly: it corrupts every payload by a few
	// bytes, which looks like a broken network rather than a broken parser.
	payload := []byte("payload")
	header := []byte{0x00, 0x00, 0x00, atypIPv4, 203, 0, 113, 9, 0x00, 0x35}
	got, err := stripSocksHeader(append(header, payload...))
	if err != nil {
		t.Fatalf("strip: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("stripped to %q", got)
	}
}

func TestAFragmentedDatagramIsRefusedRatherThanMisread(t *testing.T) {
	// FRAG != 0 means the payload is part of a larger one. Treating it as whole
	// would hand the peer a fragment pretending to be a datagram.
	header := []byte{0x00, 0x00, 0x01, atypIPv4, 203, 0, 113, 9, 0x00, 0x35}
	if _, err := stripSocksHeader(append(header, []byte("half")...)); err == nil {
		t.Fatal("a fragmented datagram was accepted")
	}
}

func TestAProxyDemandingAuthenticationSaysSoClearly(t *testing.T) {
	// Relay sends no credentials, so this has to fail with a reason a person
	// can act on rather than a timeout.
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		greeting := make([]byte, 2)
		io.ReadFull(conn, greeting)
		io.ReadFull(conn, make([]byte, greeting[1]))
		conn.Write([]byte{socksVersion, 0x02}) // username/password required
		conn.Close()
	}()

	out, _ := newUpstream(listener.Addr().String())
	_, err = out.Dial("tcp", "203.0.113.7:443")
	if err == nil {
		t.Fatal("a proxy demanding authentication was accepted")
	}
	if !strings.Contains(err.Error(), "authentication") {
		t.Fatalf("the error does not name the cause: %v", err)
	}
}
