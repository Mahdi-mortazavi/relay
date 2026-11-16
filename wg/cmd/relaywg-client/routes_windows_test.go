//go:build amd64 || arm64 || 386 || arm

package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestPlanBypassRoutesForExcludedCidrs(t *testing.T) {
	const tunLUID uint64 = 999
	const wifiLUID uint64 = 10

	rows := []routeRowSnapshot{
		{
			InterfaceLUID: wifiLUID,
			Destination:   netip.MustParsePrefix("0.0.0.0/0"),
			NextHop:       netip.MustParseAddr("192.168.1.1"),
			Metric:        25,
		},
		{
			InterfaceLUID: wifiLUID,
			Destination:   netip.MustParsePrefix("192.168.1.0/24"),
			NextHop:       netip.IPv4Unspecified(),
			Metric:        25,
		},
		{
			InterfaceLUID: wifiLUID,
			Destination:   netip.MustParsePrefix("::/0"),
			NextHop:       netip.MustParseAddr("fe80::1"),
			Metric:        25,
		},
		{
			InterfaceLUID: wifiLUID,
			Destination:   netip.MustParsePrefix("10.20.0.0/16"),
			NextHop:       netip.MustParseAddr("192.168.1.254"),
			Metric:        35,
		},
	}

	excludes := []netip.Prefix{
		netip.MustParsePrefix("192.168.1.50/32"), // covered by on-link 192.168.1.0/24
		netip.MustParsePrefix("203.0.113.0/24"),  // remote excluded CIDR -> needs physical gw
		netip.MustParsePrefix("2001:db8::/32"),   // IPv6 excluded CIDR -> needs physical v6 gw
	}

	specs := planBypassRoutes(rows, tunLUID, excludes, true)
	if len(specs) != 3 {
		t.Fatalf("planned %d routes, want 3: %+v", len(specs), specs)
	}

	want := map[string]string{
		"203.0.113.0/24": "192.168.1.1",
		"2001:db8::/32":  "fe80::1",
		"10.20.0.0/16":   "192.168.1.254",
	}
	for _, s := range specs {
		if s.Metric != 1 {
			t.Errorf("route %s metric = %d, want 1", s.Destination, s.Metric)
		}
		if w, ok := want[s.Destination.String()]; !ok || s.NextHop.String() != w {
			t.Errorf("unexpected planned route %+v", s)
		}
		delete(want, s.Destination.String())
	}
	if len(want) != 0 {
		t.Fatalf("missing planned routes: %v", want)
	}
}

func TestUdpToTcpBridgeRoundTrip(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	// Echo framed packets back over TCP.
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		var hdr [2]byte
		buf := make([]byte, 2048)
		for {
			if _, err := io.ReadFull(c, hdr[:]); err != nil {
				return
			}
			n := int(binary.BigEndian.Uint16(hdr[:]))
			if _, err := io.ReadFull(c, buf[:n]); err != nil {
				return
			}
			frame := append([]byte{hdr[0], hdr[1]}, buf[:n]...)
			_, _ = c.Write(frame)
		}
	}()

	bridge, err := startUdpToTcpBridge(ln.Addr().String())
	if err != nil {
		t.Fatalf("startUdpToTcpBridge: %v", err)
	}
	defer bridge.Close()

	client, err := net.Dial("udp4", bridge.LocalEndpoint())
	if err != nil {
		t.Fatalf("dial local bridge udp: %v", err)
	}
	defer client.Close()

	payload := []byte("wg-handshake-sim")
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := client.Write(payload); err != nil {
		t.Fatalf("udp write: %v", err)
	}
	got := make([]byte, len(payload)+16)
	n, err := client.Read(got)
	if err != nil {
		t.Fatalf("udp read: %v", err)
	}
	if !bytes.Equal(got[:n], payload) {
		t.Fatalf("roundtrip = %q, want %q", got[:n], payload)
	}
}

func TestValidateWgTcpBridgeAddr(t *testing.T) {
	for _, good := range []string{
		"127.0.0.1:51821",
		"127.0.1.1:51821",
		"localhost:51821",
		"[::1]:51821",
		" 127.0.0.1:51821 ",
	} {
		if _, err := validateWgTcpBridgeAddr(good); err != nil {
			t.Errorf("validateWgTcpBridgeAddr(%q) unexpected error: %v", good, err)
		}
	}

	for _, bad := range []string{
		"",
		"127.0.0.1",
		"127.0.0.1:0",
		"127.0.0.1:70000",
		"192.168.1.50:51821",
		"10.0.0.1:51821",
		"0.0.0.0:51821",
		"[::]:51821",
		"example.com:51821",
	} {
		if _, err := validateWgTcpBridgeAddr(bad); err == nil {
			t.Errorf("validateWgTcpBridgeAddr(%q) succeeded, expected error", bad)
		}
	}

	if _, err := startUdpToTcpBridge("192.168.1.50:51821"); err == nil {
		t.Fatal("startUdpToTcpBridge accepted non-loopback address")
	}
}

func TestReplaceIpcEndpoint(t *testing.T) {
	in := "private_key=aaa\npublic_key=bbb\nendpoint=192.168.1.5:51820\nallowed_ip=0.0.0.0/0\n"
	got := replaceIpcEndpoint(in, "127.0.0.1:55555")
	want := "private_key=aaa\npublic_key=bbb\nendpoint=127.0.0.1:55555\nallowed_ip=0.0.0.0/0\n"
	if got != want {
		t.Fatalf("got:\n%s\nwant:\n%s", got, want)
	}
}
