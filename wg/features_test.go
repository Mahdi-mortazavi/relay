package relaywg

import (
	"bytes"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func wireguardClientAt(t *testing.T, privateKey, serverPublicKey, clientIP string, serverPort int) (*device.Device, *netstack.Net) {
	t.Helper()

	addr := netip.MustParseAddr(clientIP)
	tunDev, clientNet, err := netstack.CreateNetTUN([]netip.Addr{addr}, []netip.Addr{netip.MustParseAddr(tunnelAddress)}, mtu)
	if err != nil {
		t.Fatalf("CreateNetTUN: %v", err)
	}
	client := device.NewDevice(tunDev, newResilientBind(), device.NewLogger(device.LogLevelError, "client-test: "))
	t.Cleanup(func() { client.Close() })

	cfg := fmt.Sprintf(
		"private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\n",
		privateKey, serverPublicKey, serverPort,
	)
	if err := client.IpcSet(cfg); err != nil {
		t.Fatalf("client IpcSet: %v", err)
	}
	if err := client.Up(); err != nil {
		t.Fatalf("client Up: %v", err)
	}
	return client, clientNet
}

func TestMultiPeerSimultaneousTrafficAndRemoval(t *testing.T) {
	destination := echoServer(t)

	serverPriv, serverPub := keyPair(t)
	client1Priv, client1Pub := keyPair(t)
	client2Priv, client2Pub := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\nallowed_ip=fd13:37::2/128\n",
		serverPriv, port, client1Pub,
	))
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer endpoint.Stop()

	if endpoint.PeerCount() != 1 {
		t.Fatalf("initial PeerCount = %d, want 1", endpoint.PeerCount())
	}

	_, net1 := wireguardClientAt(t, client1Priv, serverPub, "10.13.37.2", port)

	// Dynamically add second simultaneous peer without restarting endpoint.
	if err := endpoint.AddPeer(client2Pub, "10.13.37.3/32,fd13:37::3/128"); err != nil {
		t.Fatalf("AddPeer: %v", err)
	}
	if endpoint.PeerCount() != 2 {
		t.Fatalf("PeerCount after AddPeer = %d, want 2", endpoint.PeerCount())
	}

	_, net2 := wireguardClientAt(t, client2Priv, serverPub, "10.13.37.3", port)

	if err := exchangeThrough(net1, destination, 15*time.Second); err != nil {
		t.Fatalf("peer 1 exchange failed: %v", err)
	}
	if err := exchangeThrough(net2, destination, 15*time.Second); err != nil {
		t.Fatalf("peer 2 exchange failed: %v", err)
	}

	var stats []PeerStats
	if err := json.Unmarshal([]byte(endpoint.PeersStatsJSON()), &stats); err != nil {
		t.Fatalf("PeersStatsJSON unmarshal: %v", err)
	}
	if len(stats) != 2 {
		t.Fatalf("PeersStatsJSON count = %d, want 2", len(stats))
	}
	if endpoint.LastHandshakeUnix() == 0 {
		t.Fatal("LastHandshakeUnix = 0, expected handshake timestamp")
	}

	if err := endpoint.RemovePeer(client2Pub); err != nil {
		t.Fatalf("RemovePeer: %v", err)
	}
	if endpoint.PeerCount() != 1 {
		t.Fatalf("PeerCount after RemovePeer = %d, want 1", endpoint.PeerCount())
	}
	// Peer 1 must stay alive untouched!
	if err := exchangeThrough(net1, destination, 10*time.Second); err != nil {
		t.Fatalf("peer 1 broke after peer 2 removal: %v", err)
	}
}

func TestDualStackIPv6TunnelAndICMP(t *testing.T) {
	// Verify IPv6 echo to fd13:37::1 succeeds while off-tunnel IPv6 echo is dropped.
	device, err := newNetTun(mtu)
	if err != nil {
		t.Fatalf("newNetTun: %v", err)
	}
	defer device.Close()

	// Ping fd13:37::1
	pktToSelf := buildIPv6Echo("fd13:37::2", tunnelAddressV6, 0x1111)
	if _, err := device.Write([][]byte{pktToSelf}, 0); err != nil {
		t.Fatalf("write ipv6 echo self: %v", err)
	}
	ans := waitForPacket(device, 2*time.Second)
	if len(ans) < 48 || ans[0]>>4 != 6 || ans[6] != protocolICMPv6 || ans[40] != 129 {
		t.Fatalf("expected ICMPv6 echo reply from %s, got % x", tunnelAddressV6, ans)
	}

	// Ping external IPv6 -> MUST NOT answer
	pktExternal := buildIPv6Echo("fd13:37::2", "2001:4860:4860::8888", 0x2222)
	if _, err := device.Write([][]byte{pktExternal}, 0); err != nil {
		t.Fatalf("write ipv6 external: %v", err)
	}
	if bad := waitForPacket(device, 300*time.Millisecond); bad != nil {
		t.Fatalf("unexpected ICMPv6 reply for unreachable external IPv6: % x", bad)
	}
}

func buildIPv6Echo(src, dst string, id uint16) []byte {
	srcIP := net.ParseIP(src).To16()
	dstIP := net.ParseIP(dst).To16()
	const icmpLen = 12
	pkt := make([]byte, 40+icmpLen)
	pkt[0] = 0x60
	binary.BigEndian.PutUint16(pkt[4:6], icmpLen)
	pkt[6] = protocolICMPv6
	pkt[7] = 64
	copy(pkt[8:24], srcIP)
	copy(pkt[24:40], dstIP)

	icmp := pkt[40:]
	icmp[0] = icmpv6EchoRequest
	binary.BigEndian.PutUint16(icmp[4:6], id)
	binary.BigEndian.PutUint16(icmp[6:8], 1)
	copy(icmp[8:], []byte("ping"))

	// ICMPv6 pseudo-header checksum
	var sum uint32
	addBytes := func(b []byte) {
		for i := 0; i+1 < len(b); i += 2 {
			sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
		}
	}
	addBytes(srcIP)
	addBytes(dstIP)
	var upper [8]byte
	binary.BigEndian.PutUint32(upper[0:4], icmpLen)
	upper[7] = protocolICMPv6
	addBytes(upper[:])
	addBytes(icmp)
	for sum > 0xffff {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	binary.BigEndian.PutUint16(icmp[2:4], ^uint16(sum))
	return pkt
}

func TestDoHInTunnelResolution(t *testing.T) {
	// Mock HTTPS DoH server on localhost (Wait: httptest uses 127.0.0.1 which
	// endpoint's direct.Dial can reach without touching external internet).
	expectedReply := buildMockDNSReply(0x1234, net.IPv4(93, 184, 216, 34))
	dohSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Content-Type") != "application/dns-message" {
			http.Error(w, "bad content-type", http.StatusUnsupportedMediaType)
			return
		}
		reqBody, _ := io.ReadAll(r.Body)
		if len(reqBody) < 12 {
			http.Error(w, "short", http.StatusBadRequest)
			return
		}
		reply := append([]byte(nil), expectedReply...)
		copy(reply[:2], reqBody[:2])
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(reply)
	}))
	defer dohSrv.Close()

	// Point endpoint's tunnelDNS at our httptest TLS server's client config
	serverPriv, serverPub := keyPair(t)
	clientPriv, clientPub := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPriv, port, clientPub,
	))
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer endpoint.Stop()

	// Configure DoH using httptest TLS client transport wrapped around endpoint.tun.dial
	if err := endpoint.SetTunnelDNS("", dohSrv.URL); err != nil {
		t.Fatalf("SetTunnelDNS: %v", err)
	}
	// Inject httptest TLS verification config so self-signed test cert succeeds.
	endpoint.tun.dns.mu.Lock()
	endpoint.tun.dns.cfg.client = dohSrv.Client()
	endpoint.tun.dns.mu.Unlock()

	_, clientNet := wireguardClient(t, clientPriv, serverPub, port)

	// Send UDP DNS query to 10.13.37.1:53 through the live tunnel.
	udpConn, err := clientNet.Dial("udp", net.JoinHostPort(tunnelAddress, "53"))
	if err != nil {
		t.Fatalf("dial tunnel DNS: %v", err)
	}
	defer udpConn.Close()
	_ = udpConn.SetDeadline(time.Now().Add(10 * time.Second))

	query := buildSimpleAQuery("example.com", 0xABCD)
	if _, err := udpConn.Write(query); err != nil {
		t.Fatalf("write dns query: %v", err)
	}
	resp := make([]byte, 512)
	n, err := udpConn.Read(resp)
	if err != nil {
		t.Fatalf("read dns response: %v", err)
	}
	if n < 12 || resp[0] != 0xAB || resp[1] != 0xCD {
		t.Fatalf("unexpected DoH DNS reply: %x", resp[:n])
	}
}

func buildMockDNSReply(id uint16, ip net.IP) []byte {
	var b bytes.Buffer
	var hdr [12]byte
	binary.BigEndian.PutUint16(hdr[0:2], id)
	binary.BigEndian.PutUint16(hdr[2:4], 0x8180)
	binary.BigEndian.PutUint16(hdr[6:8], 1)
	b.Write(hdr[:])
	// Minimal answer RR (pointer to offset 12, A record, TTL 60, 4 bytes)
	b.Write([]byte{0xC0, 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04})
	b.Write(ip.To4())
	return b.Bytes()
}

func TestInboundPortForwardingAndFlowsAndQuota(t *testing.T) {
	serverPriv, serverPub := keyPair(t)
	clientPriv, clientPub := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPriv, port, clientPub,
	))
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer endpoint.Stop()

	_, clientNet := wireguardClient(t, clientPriv, serverPub, port)

	// Client inside tunnel listens on 10.13.37.2:18080 (simulating local web/RDP/SSH on PC).
	inLn, err := clientNet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.13.37.2"), Port: 18080})
	if err != nil {
		t.Fatalf("clientNet.ListenTCP: %v", err)
	}
	defer inLn.Close()
	go func() {
		for {
			c, err := inLn.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}(c)
		}
	}()

	// Warm up handshake first so peer endpoint exists inside wireguard-go.
	dest := echoServer(t)
	if err := exchangeThrough(clientNet, dest, 15*time.Second); err != nil {
		t.Fatalf("warmup failed: %v", err)
	}

	// Phone listens on LAN/loopback port and forwards inbound into 10.13.37.2:18080.
	fwdPort := freeTCPPort(t)
	if err := endpoint.AddInboundForward(fwdPort, "10.13.37.2", 18080, "tcp"); err != nil {
		t.Fatalf("AddInboundForward: %v", err)
	}

	ext, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", fwdPort), 5*time.Second)
	if err != nil {
		t.Fatalf("dial inbound forward: %v", err)
	}
	_ = ext.SetDeadline(time.Now().Add(5 * time.Second))
	msg := []byte("inbound-lan-to-pc")
	if _, err := ext.Write(msg); err != nil {
		t.Fatalf("write inbound: %v", err)
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(ext, got); err != nil {
		t.Fatalf("read inbound: %v", err)
	}
	_ = ext.Close()
	if !bytes.Equal(got, msg) {
		t.Fatalf("inbound forward got %q, want %q", got, msg)
	}

	if !strings.Contains(endpoint.InboundForwardsJSON(), "10.13.37.2") {
		t.Fatalf("InboundForwardsJSON missing rule: %s", endpoint.InboundForwardsJSON())
	}

	// Now enforce a tight quota and verify quota exhaustion stops new dials cleanly.
	endpoint.SetTrafficLimits(0, 0, endpoint.QuotaUsedBytes()+10)
	// Exceed the 10-byte remaining quota.
	_ = exchangeThrough(clientNet, dest, 5*time.Second)
	if !endpoint.QuotaExceeded() {
		t.Fatal("expected QuotaExceeded() == true")
	}
	if err := exchangeThrough(clientNet, dest, 2*time.Second); err == nil {
		t.Fatal("expected dial after quota exceeded to fail")
	}
}

func TestWireGuardOverTcpBridge(t *testing.T) {
	destination := echoServer(t)
	serverPriv, serverPub := keyPair(t)
	clientPriv, clientPub := keyPair(t)
	wgPort := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPriv, wgPort, clientPub,
	))
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer endpoint.Stop()

	bridge, err := StartTcpBridge("127.0.0.1:0", wgPort)
	if err != nil {
		t.Fatalf("StartTcpBridge: %v", err)
	}
	defer bridge.Stop()

	// Simulate client side of the ADB TCP bridge using a local UDP socket bridged to bridge.Port().
	localUDP, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatalf("listen local UDP: %v", err)
	}
	defer localUDP.Close()

	tcpConn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", bridge.Port()), 3*time.Second)
	if err != nil {
		t.Fatalf("dial tcp bridge: %v", err)
	}
	defer tcpConn.Close()

	var peerUDP *net.UDPAddr
	peerReady := make(chan struct{})
	var once sync.Once
	go func() {
		buf := make([]byte, 65536)
		for {
			n, from, err := localUDP.ReadFromUDP(buf)
			if err != nil {
				return
			}
			once.Do(func() {
				peerUDP = from
				close(peerReady)
			})
			var hdr [2]byte
			binary.BigEndian.PutUint16(hdr[:], uint16(n))
			_, _ = tcpConn.Write(append(hdr[:], buf[:n]...))
		}
	}()
	go func() {
		<-peerReady
		var hdr [2]byte
		buf := make([]byte, 65536)
		for {
			if _, err := io.ReadFull(tcpConn, hdr[:]); err != nil {
				return
			}
			n := int(binary.BigEndian.Uint16(hdr[:]))
			if _, err := io.ReadFull(tcpConn, buf[:n]); err != nil {
				return
			}
			_, _ = localUDP.WriteToUDP(buf[:n], peerUDP)
		}
	}()

	bridgedPort := localUDP.LocalAddr().(*net.UDPAddr).Port
	_, clientNet := wireguardClientAt(t, clientPriv, serverPub, "10.13.37.2", bridgedPort)
	if err := exchangeThrough(clientNet, destination, 15*time.Second); err != nil {
		t.Fatalf("traffic over WireGuard TCP bridge failed: %v", err)
	}

	if _, err := hex.DecodeString(clientPub); err != nil {
		t.Fatal(err)
	}
}

func TestTcpBridgeStopWithIdleClient(t *testing.T) {
	wgSink, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatalf("listen wgSink: %v", err)
	}
	defer wgSink.Close()
	wgPort := wgSink.LocalAddr().(*net.UDPAddr).Port

	bridge, err := StartTcpBridge("127.0.0.1:0", wgPort)
	if err != nil {
		t.Fatalf("StartTcpBridge: %v", err)
	}

	tcpConn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", bridge.Port()), 3*time.Second)
	if err != nil {
		bridge.Stop()
		t.Fatalf("dial tcp bridge: %v", err)
	}
	defer tcpConn.Close()

	// Pump one datagram so handleClient is deterministically active and idle in Read.
	payload := []byte("ping")
	var frame [6]byte
	binary.BigEndian.PutUint16(frame[:2], uint16(len(payload)))
	copy(frame[2:], payload)
	if _, err := tcpConn.Write(frame[:]); err != nil {
		bridge.Stop()
		t.Fatalf("write frame: %v", err)
	}

	_ = wgSink.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 64)
	n, _, err := wgSink.ReadFromUDP(buf)
	if err != nil || !bytes.Equal(buf[:n], payload) {
		bridge.Stop()
		t.Fatalf("wgSink read = (%q, %v), want (%q, nil)", buf[:n], err, payload)
	}

	stopped := make(chan struct{})
	go func() {
		bridge.Stop()
		close(stopped)
	}()

	select {
	case <-stopped:
	case <-time.After(2 * time.Second):
		t.Fatal("TcpBridge.Stop() deadlocked with an idle client connection open")
	}

	_ = tcpConn.SetReadDeadline(time.Now().Add(time.Second))
	var one [1]byte
	if _, err := tcpConn.Read(one[:]); err == nil {
		t.Fatal("expected idle client TCP connection to be closed after Stop()")
	}
}

func TestTetherTTLAppliesToDirectUpstream(t *testing.T) {
	tunDev, err := newNetTun(mtu)
	if err != nil {
		t.Fatalf("newNetTun: %v", err)
	}
	defer tunDev.Close()

	// Simulate StartVia("", "") and SetUpstream("") both storing direct{ttl: nil}.
	out, err := newUpstream("")
	if err != nil {
		t.Fatalf("newUpstream: %v", err)
	}
	tunDev.setUpstream(out)
	tunDev.tetherTTL.Store(65)

	// Verify dial preserves d.tetherTTL on direct{}.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	go func() {
		if c, err := ln.Accept(); err == nil {
			_ = c.Close()
		}
	}()

	ref := tunDev.out.Load()
	if ref == nil {
		t.Fatal("expected non-nil out")
	}
	d, ok := ref.upstream.(direct)
	if !ok {
		t.Fatalf("expected direct upstream, got %T", ref.upstream)
	}
	if d.ttl != nil {
		t.Fatal("expected stored direct{} to have nil ttl before netTun.dial wraps it")
	}
	c, err := tunDev.dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("tunDev.dial: %v", err)
	}
	_ = c.Close()

	// EnableICMPRelay must also not nil-deref if out is cleared.
	tunDev.out.Store(nil)
	ep := &Endpoint{tun: tunDev}
	_ = ep.EnableICMPRelay(false)
}

func TestAddPeerReplacesAllowedIPs(t *testing.T) {
	serverPriv, _ := keyPair(t)
	_, clientPub := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPriv, port, clientPub,
	))
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer endpoint.Stop()

	if err := endpoint.AddPeer(clientPub, "10.13.37.9/32"); err != nil {
		t.Fatalf("AddPeer update: %v", err)
	}
	status, err := endpoint.dev.IpcGet()
	if err != nil {
		t.Fatalf("IpcGet: %v", err)
	}
	if strings.Contains(status, "allowed_ip=10.13.37.2/32") {
		t.Fatalf("AddPeer appended instead of replacing allowed_ip: %s", status)
	}
	if !strings.Contains(status, "allowed_ip=10.13.37.9/32") {
		t.Fatalf("AddPeer missing new allowed_ip: %s", status)
	}
}

func TestInboundForwardUpdateRebindAndUDPFlow(t *testing.T) {
	serverPriv, serverPub := keyPair(t)
	clientPriv, clientPub := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPriv, port, clientPub,
	))
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer endpoint.Stop()

	_, clientNet := wireguardClient(t, clientPriv, serverPub, port)
	dest := echoServer(t)
	if err := exchangeThrough(clientNet, dest, 15*time.Second); err != nil {
		t.Fatalf("warmup: %v", err)
	}

	// Re-applying identical and updated target on the same port must not fail with EADDRINUSE.
	fwdPort := freeTCPPort(t)
	if err := endpoint.AddInboundForward(fwdPort, "10.13.37.2", 18080, "tcp"); err != nil {
		t.Fatalf("AddInboundForward initial: %v", err)
	}
	if err := endpoint.AddInboundForward(fwdPort, "10.13.37.2", 18080, "tcp"); err != nil {
		t.Fatalf("AddInboundForward idempotent reapply: %v", err)
	}
	if err := endpoint.AddInboundForward(fwdPort, "10.13.37.2", 18081, "tcp"); err != nil {
		t.Fatalf("AddInboundForward target update: %v", err)
	}
	if !strings.Contains(endpoint.InboundForwardsJSON(), `"targetPort":18081`) {
		t.Fatalf("InboundForwardsJSON did not update targetPort: %s", endpoint.InboundForwardsJSON())
	}

	// Verify UDP inbound port forward tracks flows in ActiveFlowsJSON.
	udpLn, err := clientNet.ListenUDP(&net.UDPAddr{IP: net.ParseIP("10.13.37.2"), Port: 18090})
	if err != nil {
		t.Fatalf("clientNet.ListenUDP: %v", err)
	}
	defer udpLn.Close()
	go func() {
		buf := make([]byte, 1024)
		for {
			n, addr, rerr := udpLn.ReadFrom(buf)
			if rerr != nil {
				return
			}
			_, _ = udpLn.WriteTo(buf[:n], addr)
		}
	}()

	udpFwdPort := freeUDPPort(t)
	if err := endpoint.AddInboundForward(udpFwdPort, "10.13.37.2", 18090, "udp"); err != nil {
		t.Fatalf("AddInboundForward udp: %v", err)
	}
	extUDP, err := net.Dial("udp", fmt.Sprintf("127.0.0.1:%d", udpFwdPort))
	if err != nil {
		t.Fatalf("dial udp forward: %v", err)
	}
	defer extUDP.Close()
	_ = extUDP.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := extUDP.Write([]byte("udp-fwd")); err != nil {
		t.Fatalf("write udp forward: %v", err)
	}
	reply := make([]byte, 32)
	rn, err := extUDP.Read(reply)
	if err != nil || string(reply[:rn]) != "udp-fwd" {
		t.Fatalf("read udp forward = (%q, %v)", reply[:rn], err)
	}
	if !strings.Contains(endpoint.ActiveFlowsJSON(), `"udp-in"`) {
		t.Fatalf("expected udp-in flow in ActiveFlowsJSON, got: %s", endpoint.ActiveFlowsJSON())
	}
}

func TestDoHInterceptsArbitraryResolverPort53WhenEnabled(t *testing.T) {
	expectedReply := buildMockDNSReply(0x1234, net.IPv4(93, 184, 216, 34))
	dohSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reqBody, _ := io.ReadAll(r.Body)
		if len(reqBody) < 12 {
			http.Error(w, "short", http.StatusBadRequest)
			return
		}
		reply := append([]byte(nil), expectedReply...)
		copy(reply[:2], reqBody[:2])
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(reply)
	}))
	defer dohSrv.Close()

	serverPriv, serverPub := keyPair(t)
	clientPriv, clientPub := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPriv, port, clientPub,
	))
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer endpoint.Stop()

	if err := endpoint.SetTunnelDNS("", dohSrv.URL); err != nil {
		t.Fatalf("SetTunnelDNS: %v", err)
	}
	endpoint.tun.dns.mu.Lock()
	endpoint.tun.dns.cfg.client = dohSrv.Client()
	endpoint.tun.dns.mu.Unlock()

	_, clientNet := wireguardClient(t, clientPriv, serverPub, port)

	// Query 1.1.1.1:53 (standard client resolver IP) -> must still route via DoH!
	udpConn, err := clientNet.Dial("udp", "1.1.1.1:53")
	if err != nil {
		t.Fatalf("dial 1.1.1.1:53: %v", err)
	}
	defer udpConn.Close()
	_ = udpConn.SetDeadline(time.Now().Add(10 * time.Second))

	query := buildSimpleAQuery("example.com", 0xBEEF)
	if _, err := udpConn.Write(query); err != nil {
		t.Fatalf("write dns query: %v", err)
	}
	resp := make([]byte, 512)
	n, err := udpConn.Read(resp)
	if err != nil {
		t.Fatalf("read dns response: %v", err)
	}
	if n < 12 || resp[0] != 0xBE || resp[1] != 0xEF {
		t.Fatalf("unexpected DoH DNS reply: %x", resp[:n])
	}
}

func freeTCPPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("freeTCPPort: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return port
}
