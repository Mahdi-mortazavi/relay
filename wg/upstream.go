package relaywg

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"
)

// An upstream is where the phone sends the traffic it is forwarding for the PC.
//
// Why this exists at all is a trap the product walked into and had to measure
// its way out of. The forwarders open ordinary Go sockets, so they take the
// phone's default route -- through its VPN when one is up, which is the whole
// feature. But an Android VPN routes by UID, so it also swallows the phone's
// *replies* to the PC, and the pairing never completes. The fix everyone
// reaches for is to exclude Relay in the VPN app's per-app list, and that works
// -- at the cost of the thing they wanted:
//
//	ip route get 1.1.1.1 uid 10698  ->  dev wlan0   (Relay, excluded)
//	ip route get 1.1.1.1 uid 10103  ->  dev tun0    (any other app)
//
// Measured on hardware. Excluded from the tunnel, Relay forwards over the
// phone's ordinary connection, so the PC gets the phone's internet and not the
// phone's VPN. You could have the connection or the VPN, never both.
//
// Most VPN clients of the kind this app's users run -- v2ray, sing-box,
// Hiddify, Oblivion -- also expose a local SOCKS5 port. Pointing the forwarders
// at that port closes the gap: Relay stays outside the tunnel so its replies
// reach the LAN, and the traffic it forwards goes *into* the tunnel through the
// proxy. Both, at last.
type upstream interface {
	// Dial opens a connection to address on the phone's behalf. network is
	// "tcp" or "udp"; address is always host:port with a literal IP host,
	// because it comes from a packet's destination and never from a name.
	Dial(network, address string) (net.Conn, error)
}

// direct is the original behaviour and stays the default: an ordinary Go socket
// on the phone's default route.
type direct struct{}

func (direct) Dial(network, address string) (net.Conn, error) {
	return net.DialTimeout(network, address, dialTimeout)
}

// socks5 forwards through a SOCKS5 proxy, which on a phone means a VPN client's
// own local port.
//
// Hand-written rather than pulled from golang.org/x/net/proxy for two reasons:
// that package has no UDP, and Relay's headline claim is that it carries TCP
// *and* UDP so that calls and games work. A proxy mode that silently dropped
// every UDP flow would be the app quietly becoming a browser tunnel. The
// protocol is RFC 1928 and the useful part of it is short.
//
// No authentication is offered. These proxies listen on 127.0.0.1 on the same
// phone; a username and password would be a field to fill in that protects
// nothing, and every client this targets defaults to no auth.
type socks5 struct {
	address string
}

const (
	socksVersion   = 0x05
	socksNoAuth    = 0x00
	cmdConnect     = 0x01
	cmdUDPAssoc    = 0x03
	atypIPv4       = 0x01
	atypDomainName = 0x03
	atypIPv6       = 0x04
	replySucceeded = 0x00
)

// socksTimeout bounds the handshake only. What the connection does afterwards
// is the caller's business, and a long-lived download must not be cut short by
// a deadline meant for a five-byte greeting.
const socksTimeout = 8 * time.Second

func (s socks5) Dial(network, address string) (net.Conn, error) {
	switch network {
	case "tcp", "tcp4", "tcp6":
		return s.connect(address)
	case "udp", "udp4", "udp6":
		return s.associate(address)
	default:
		return nil, fmt.Errorf("relaywg: upstream cannot carry %q", network)
	}
}

// connect is RFC 1928's CONNECT: the proxy dials for us and the same TCP
// connection then carries the payload.
func (s socks5) connect(address string) (net.Conn, error) {
	control, err := net.DialTimeout("tcp", s.address, dialTimeout)
	if err != nil {
		return nil, fmt.Errorf("relaywg: upstream proxy unreachable: %w", err)
	}
	if _, _, err := s.handshake(control, cmdConnect, address); err != nil {
		control.Close()
		return nil, err
	}
	return control, nil
}

// associate is RFC 1928's UDP ASSOCIATE, which is the awkward one.
//
// The proxy answers with an address to send datagrams to, and every datagram
// carries a header naming its final destination. The TCP connection that
// requested the association carries no data at all -- it is a lifetime handle,
// and the proxy tears the association down when it closes. So it is kept, and
// closed with the returned connection.
func (s socks5) associate(address string) (net.Conn, error) {
	control, err := net.DialTimeout("tcp", s.address, dialTimeout)
	if err != nil {
		return nil, fmt.Errorf("relaywg: upstream proxy unreachable: %w", err)
	}

	// 0.0.0.0:0 means "I do not know yet which source I will send from", which
	// is the honest answer: the socket is not bound until the line below.
	boundHost, boundPort, err := s.handshake(control, cmdUDPAssoc, "0.0.0.0:0")
	if err != nil {
		control.Close()
		return nil, err
	}
	// A proxy that answers 0.0.0.0 means "the address you already reached me
	// on", which is the common case for a local one.
	if boundHost == "0.0.0.0" || boundHost == "::" {
		if host, _, splitErr := net.SplitHostPort(s.address); splitErr == nil {
			boundHost = host
		}
	}

	relay, err := net.Dial("udp", net.JoinHostPort(boundHost, strconv.Itoa(boundPort)))
	if err != nil {
		control.Close()
		return nil, fmt.Errorf("relaywg: upstream proxy refused datagrams: %w", err)
	}

	header, err := socksAddress(address)
	if err != nil {
		control.Close()
		relay.Close()
		return nil, err
	}
	return &socksPacketConn{Conn: relay, control: control, header: header}, nil
}

// handshake greets the proxy, sends one request, and returns the address it
// replied with.
func (s socks5) handshake(control net.Conn, command byte, address string) (string, int, error) {
	if err := control.SetDeadline(time.Now().Add(socksTimeout)); err != nil {
		return "", 0, err
	}
	// Clearing it matters: for CONNECT this same connection becomes the
	// payload, and leaving the handshake deadline on it would kill every
	// transfer that outlived eight seconds.
	defer control.SetDeadline(time.Time{})

	// Greeting: version, one method, no authentication.
	if _, err := control.Write([]byte{socksVersion, 1, socksNoAuth}); err != nil {
		return "", 0, fmt.Errorf("relaywg: upstream greeting failed: %w", err)
	}
	reply := make([]byte, 2)
	if _, err := io.ReadFull(control, reply); err != nil {
		return "", 0, fmt.Errorf("relaywg: upstream did not greet back: %w", err)
	}
	if reply[0] != socksVersion || reply[1] != socksNoAuth {
		return "", 0, errors.New("relaywg: upstream proxy wants authentication, which Relay does not send")
	}

	target, err := socksAddress(address)
	if err != nil {
		return "", 0, err
	}
	request := append([]byte{socksVersion, command, 0x00}, target...)
	if _, err := control.Write(request); err != nil {
		return "", 0, fmt.Errorf("relaywg: upstream request failed: %w", err)
	}
	return readSocksReply(control)
}

// readSocksReply parses VER REP RSV ATYP ADDR PORT.
func readSocksReply(r io.Reader) (string, int, error) {
	head := make([]byte, 4)
	if _, err := io.ReadFull(r, head); err != nil {
		return "", 0, fmt.Errorf("relaywg: upstream gave no reply: %w", err)
	}
	if head[0] != socksVersion {
		return "", 0, errors.New("relaywg: upstream is not speaking SOCKS5")
	}
	if head[1] != replySucceeded {
		return "", 0, fmt.Errorf("relaywg: upstream refused, code %d", head[1])
	}

	var host string
	switch head[3] {
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
		return "", 0, fmt.Errorf("relaywg: upstream replied with address type %d", head[3])
	}

	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(r, portBytes); err != nil {
		return "", 0, err
	}
	return host, int(binary.BigEndian.Uint16(portBytes)), nil
}

// socksAddress encodes host:port as ATYP + address + port.
func socksAddress(address string) ([]byte, error) {
	host, portText, err := net.SplitHostPort(address)
	if err != nil {
		return nil, fmt.Errorf("relaywg: %q is not host:port: %w", address, err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 0 || port > 65535 {
		return nil, fmt.Errorf("relaywg: %q has no usable port", address)
	}

	var encoded []byte
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			encoded = append([]byte{atypIPv4}, v4...)
		} else {
			encoded = append([]byte{atypIPv6}, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return nil, fmt.Errorf("relaywg: host name too long for SOCKS5")
		}
		encoded = append([]byte{atypDomainName, byte(len(host))}, host...)
	}
	return binary.BigEndian.AppendUint16(encoded, uint16(port)), nil
}

// socksPacketConn makes one SOCKS5 UDP association look like a plain net.Conn
// to a single destination, which is what the forwarder hands out.
//
// Every datagram out gains the RFC 1928 header; every datagram in loses it. The
// destination never changes for the life of this connection -- the forwarder
// creates one of these per flow -- so the header is built once.
type socksPacketConn struct {
	net.Conn
	control net.Conn
	header  []byte
}

func (c *socksPacketConn) Write(payload []byte) (int, error) {
	// RSV RSV FRAG, then the address, then the payload. FRAG 0: Relay never
	// fragments, and a proxy that requires it is one this cannot use anyway.
	datagram := make([]byte, 0, 3+len(c.header)+len(payload))
	datagram = append(datagram, 0x00, 0x00, 0x00)
	datagram = append(datagram, c.header...)
	datagram = append(datagram, payload...)
	if _, err := c.Conn.Write(datagram); err != nil {
		return 0, err
	}
	// Report the caller's own length. Returning the wrapped length would make
	// io.Copy believe it wrote more than it was given, which is a short-write
	// error in the other direction.
	return len(payload), nil
}

func (c *socksPacketConn) Read(into []byte) (int, error) {
	buffer := make([]byte, len(into)+maxSocksHeader)
	n, err := c.Conn.Read(buffer)
	if err != nil {
		return 0, err
	}
	payload, err := stripSocksHeader(buffer[:n])
	if err != nil {
		return 0, err
	}
	if len(payload) > len(into) {
		// The caller's buffer is the MTU; anything longer than that could not
		// have crossed the tunnel to get here.
		return 0, io.ErrShortBuffer
	}
	return copy(into, payload), nil
}

func (c *socksPacketConn) Close() error {
	// The control connection is the association's lifetime. Closing it is what
	// tells the proxy to let the port go.
	err := c.Conn.Close()
	if controlErr := c.control.Close(); err == nil {
		err = controlErr
	}
	return err
}

// maxSocksHeader is 3 reserved bytes + ATYP + a 16-byte address + 2 port bytes,
// with room to spare for a domain-name reply no proxy should be sending here.
const maxSocksHeader = 262

// stripSocksHeader removes the per-datagram header a proxy puts in front of a
// reply. The address in it is where the datagram came *from*, which this
// deliberately ignores: the forwarder's connection already knows its peer, and
// trusting a proxy-supplied source address would be taking routing advice from
// the wire.
func stripSocksHeader(datagram []byte) ([]byte, error) {
	if len(datagram) < 4 {
		return nil, errors.New("relaywg: upstream datagram is too short to be one")
	}
	if datagram[2] != 0x00 {
		return nil, errors.New("relaywg: upstream fragmented a datagram, which Relay does not reassemble")
	}
	offset := 4
	switch datagram[3] {
	case atypIPv4:
		offset += 4
	case atypIPv6:
		offset += 16
	case atypDomainName:
		if len(datagram) < 5 {
			return nil, errors.New("relaywg: upstream datagram has no host length")
		}
		offset += 1 + int(datagram[4])
	default:
		return nil, fmt.Errorf("relaywg: upstream datagram has address type %d", datagram[3])
	}
	offset += 2 // port
	if len(datagram) < offset {
		return nil, errors.New("relaywg: upstream datagram is shorter than its own header")
	}
	return datagram[offset:], nil
}

// newUpstream picks the egress from what the caller configured. An empty
// address keeps the behaviour every release so far has had.
func newUpstream(proxyAddress string) (upstream, error) {
	if proxyAddress == "" {
		return direct{}, nil
	}
	host, portText, err := net.SplitHostPort(proxyAddress)
	if err != nil {
		return nil, fmt.Errorf("relaywg: upstream proxy %q is not host:port: %w", proxyAddress, err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return nil, fmt.Errorf("relaywg: upstream proxy %q has no usable port", proxyAddress)
	}
	if host == "" {
		host = "127.0.0.1"
	}
	return socks5{address: net.JoinHostPort(host, strconv.Itoa(port))}, nil
}
