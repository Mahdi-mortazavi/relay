//go:build amd64 || arm64 || 386 || arm

package main

import (
	"net/netip"
	"testing"
	"unsafe"
)

func TestWfpStructSizesAndOffsetsMatchKernelABI(t *testing.T) {
	if got := unsafe.Sizeof(fwpmFilter0{}); got != wtFwpmFilter0Size {
		t.Fatalf("fwpmFilter0 size = %d, want %d", got, wtFwpmFilter0Size)
	}
	if got := unsafe.Sizeof(fwpmSession0{}); got != wtFwpmSession0Size {
		t.Fatalf("fwpmSession0 size = %d, want %d", got, wtFwpmSession0Size)
	}
	if got := unsafe.Sizeof(fwpmProvider0{}); got != wtFwpmProvider0Size {
		t.Fatalf("fwpmProvider0 size = %d, want %d", got, wtFwpmProvider0Size)
	}
	if got := unsafe.Sizeof(fwpmSublayer0{}); got != wtFwpmSublayer0Size {
		t.Fatalf("fwpmSublayer0 size = %d, want %d", got, wtFwpmSublayer0Size)
	}
	if got := unsafe.Sizeof(fwpmFilterCondition0{}); got != wtFwpmFilterCondition0Sz {
		t.Fatalf("fwpmFilterCondition0 size = %d, want %d", got, wtFwpmFilterCondition0Sz)
	}
	if got := unsafe.Sizeof(fwpValue0{}); got != wtFwpValue0Size {
		t.Fatalf("fwpValue0 size = %d, want %d", got, wtFwpValue0Size)
	}
	if got := unsafe.Sizeof(fwpV4AddrAndMask{}); got != 8 {
		t.Fatalf("fwpV4AddrAndMask size = %d, want 8", got)
	}
	if got := unsafe.Sizeof(fwpV6AddrAndMask{}); got != 17 {
		t.Fatalf("fwpV6AddrAndMask size = %d, want 17", got)
	}
}

func TestParsePrefixExclusions(t *testing.T) {
	got, err := parsePrefixExclusions("192.168.1.5/24, fd00::1/64")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("len = %d, want 2", len(got))
	}
	if got[0] != netip.MustParsePrefix("192.168.1.0/24") {
		t.Errorf("got[0] = %s, want 192.168.1.0/24", got[0])
	}
	if got[1] != netip.MustParsePrefix("fd00::/64") {
		t.Errorf("got[1] = %s, want fd00::/64", got[1])
	}

	for _, bad := range []string{"0.0.0.0/0", "::/0", "not-a-cidr", "192.168.1.1"} {
		if _, err := parsePrefixExclusions(bad); err == nil {
			t.Errorf("parsePrefixExclusions(%q) succeeded, expected error", bad)
		}
	}
}

func TestParseAppExclusions(t *testing.T) {
	got, err := parseAppExclusions(`"C:\Program Files\App\tool.exe", C:\Program Files\App\tool.exe, D:\bin\helper.EXE`)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("deduplicated len = %d, want 2 (%v)", len(got), got)
	}

	for _, bad := range []string{
		`tool.exe`,
		`relative\tool.exe`,
		`C:\Windows\System32\svchost.exe`,
		`C:\Windows\System32\cmd.exe`,
		`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`,
		`C:\Program Files\App\library.dll`,
	} {
		if _, err := parseAppExclusions(bad); err == nil {
			t.Errorf("parseAppExclusions(%q) succeeded, expected error", bad)
		}
	}
}

func TestShouldResetForeignConnection(t *testing.T) {
	tunnelAddr := netip.MustParseAddr("10.13.37.2")
	peerAddr := netip.MustParseAddr("192.168.1.13")
	excluded := []netip.Prefix{netip.MustParsePrefix("203.0.113.0/24")}

	cases := []struct {
		name     string
		local    string
		remote   string
		allowLAN bool
		want     bool
	}{
		{"already on tunnel", "10.13.37.2", "1.1.1.1", false, false},
		{"loopback kept", "127.0.0.1", "127.0.0.1", false, false},
		{"peer IP kept", "192.168.1.10", "192.168.1.13", false, false},
		{"public connection reset", "192.168.1.10", "149.154.167.50", false, true},
		{"RFC1918 reset when LAN not excluded", "192.168.1.10", "192.168.1.50", false, true},
		{"RFC1918 kept when LAN excluded", "192.168.1.10", "192.168.1.50", true, false},
		{"excluded CIDR kept", "192.168.1.10", "203.0.113.42", false, false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := shouldResetForeignConnection(
				netip.MustParseAddr(tc.local),
				netip.MustParseAddr(tc.remote),
				tunnelAddr,
				peerAddr,
				excluded,
				tc.allowLAN,
			)
			if got != tc.want {
				t.Errorf("got %v, want %v", got, tc.want)
			}
		})
	}
}

func TestPeerEndpointAddr(t *testing.T) {
	cfg := "private_key=abc\npublic_key=def\nendpoint=192.168.43.1:51820\nallowed_ip=0.0.0.0/0\n"
	if got := peerEndpointAddr(cfg); got != netip.MustParseAddr("192.168.43.1") {
		t.Fatalf("got %v, want 192.168.43.1", got)
	}
	if got := peerEndpointAddr("public_key=def\n"); got.IsValid() {
		t.Fatalf("expected invalid addr, got %v", got)
	}
}

func TestIPv4MaskMath(t *testing.T) {
	if got := ipv4HostUint32(netip.MustParseAddr("192.168.10.0")); got != 0xC0A80A00 {
		t.Errorf("ipv4HostUint32 = %#x, want 0xc0a80a00", got)
	}
	if got := ipv4PrefixMask(24); got != 0xFFFFFF00 {
		t.Errorf("ipv4PrefixMask(24) = %#x, want 0xffffff00", got)
	}
	if got := ipv4PrefixMask(32); got != 0xFFFFFFFF {
		t.Errorf("ipv4PrefixMask(32) = %#x, want 0xffffffff", got)
	}
}
