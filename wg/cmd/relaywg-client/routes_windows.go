//go:build amd64 || arm64 || 386 || arm

package main

import (
	"fmt"
	"net/netip"
	"os"
	"sort"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

type routeRowSnapshot struct {
	InterfaceLUID uint64
	Destination   netip.Prefix
	NextHop       netip.Addr
	Metric        uint32
	Loopback      bool
}

type bypassRouteSpec struct {
	InterfaceLUID uint64
	Destination   netip.Prefix
	NextHop       netip.Addr
	Metric        uint32
}

// installBypassRoutes adds explicit physical-gateway routes for -exclude-cidrs
// (and preserves non-tunnel LAN gateway routes under -allow-lan) so WinTun's
// metric-5 default route does not capture split-tunneled traffic.
func installBypassRoutes(
	tunLUID uint64,
	excludePrefixes []netip.Prefix,
	allowLAN bool,
) func() {
	if len(excludePrefixes) == 0 && !allowLAN {
		return func() {}
	}

	var rows []routeRowSnapshot
	for _, family := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
		table, err := winipcfg.GetIPForwardTable2(family)
		if err != nil {
			continue
		}
		for i := range table {
			row := &table[i]
			dest := row.DestinationPrefix.Prefix()
			if !dest.IsValid() {
				continue
			}
			rows = append(rows, routeRowSnapshot{
				InterfaceLUID: uint64(row.InterfaceLUID),
				Destination:   dest.Masked(),
				NextHop:       row.NextHop.Addr(),
				Metric:        row.Metric,
				Loopback:      row.Loopback,
			})
		}
	}

	specs := planBypassRoutes(rows, tunLUID, excludePrefixes, allowLAN)
	installed := make([]bypassRouteSpec, 0, len(specs))
	for _, spec := range specs {
		luid := winipcfg.LUID(spec.InterfaceLUID)
		if err := luid.AddRoute(spec.Destination, spec.NextHop, spec.Metric); err != nil {
			fmt.Fprintf(os.Stderr, "bypass route %s via %s skipped: %v\n", spec.Destination, spec.NextHop, err)
			continue
		}
		installed = append(installed, spec)
		fmt.Fprintf(os.Stderr, "bypass route active: %s via %s (metric %d)\n", spec.Destination, spec.NextHop, spec.Metric)
	}

	return func() {
		for i := len(installed) - 1; i >= 0; i-- {
			spec := installed[i]
			_ = winipcfg.LUID(spec.InterfaceLUID).DeleteRoute(spec.Destination, spec.NextHop)
		}
	}
}

func planBypassRoutes(
	rows []routeRowSnapshot,
	tunLUID uint64,
	excludePrefixes []netip.Prefix,
	allowLAN bool,
) []bypassRouteSpec {
	gw4, ok4 := findBestPhysicalGateway(rows, tunLUID, false)
	gw6, ok6 := findBestPhysicalGateway(rows, tunLUID, true)

	seen := make(map[string]struct{})
	var out []bypassRouteSpec
	add := func(spec bypassRouteSpec) {
		key := fmt.Sprintf("%d|%s|%s", spec.InterfaceLUID, spec.Destination, spec.NextHop)
		if _, dup := seen[key]; dup {
			return
		}
		seen[key] = struct{}{}
		out = append(out, spec)
	}

	for _, raw := range excludePrefixes {
		p := raw.Masked()
		if !p.IsValid() || p.Bits() == 0 {
			continue
		}
		if hasBetterOrEqualNonTunnelRoute(rows, tunLUID, p) {
			continue
		}
		if p.Addr().Is4() && ok4 {
			add(bypassRouteSpec{
				InterfaceLUID: gw4.InterfaceLUID,
				Destination:   p,
				NextHop:       gw4.NextHop,
				Metric:        1,
			})
		} else if p.Addr().Is6() && ok6 {
			add(bypassRouteSpec{
				InterfaceLUID: gw6.InterfaceLUID,
				Destination:   p,
				NextHop:       gw6.NextHop,
				Metric:        1,
			})
		}
	}

	if allowLAN {
		for _, r := range rows {
			if r.InterfaceLUID == tunLUID || r.Loopback {
				continue
			}
			if r.Destination.Bits() == 0 || !r.NextHop.IsValid() || r.NextHop.IsUnspecified() {
				continue
			}
			if isPrivateOrLinkLocalPrefix(r.Destination) && r.Metric > 1 {
				add(bypassRouteSpec{
					InterfaceLUID: r.InterfaceLUID,
					Destination:   r.Destination,
					NextHop:       r.NextHop,
					Metric:        1,
				})
			}
		}
	}

	return out
}

func findBestPhysicalGateway(rows []routeRowSnapshot, tunLUID uint64, v6 bool) (routeRowSnapshot, bool) {
	var candidates []routeRowSnapshot
	for _, r := range rows {
		if r.InterfaceLUID == tunLUID || r.Loopback {
			continue
		}
		if r.Destination.Bits() != 0 {
			continue
		}
		if v6 != r.Destination.Addr().Is6() {
			continue
		}
		if !r.NextHop.IsValid() || r.NextHop.IsUnspecified() {
			continue
		}
		candidates = append(candidates, r)
	}
	if len(candidates) == 0 {
		return routeRowSnapshot{}, false
	}
	sort.Slice(candidates, func(i, j int) bool {
		return candidates[i].Metric < candidates[j].Metric
	})
	return candidates[0], true
}

func hasBetterOrEqualNonTunnelRoute(rows []routeRowSnapshot, tunLUID uint64, p netip.Prefix) bool {
	for _, r := range rows {
		if r.InterfaceLUID == tunLUID || r.Loopback {
			continue
		}
		if r.Destination == p && r.Metric <= 1 {
			return true
		}
		// Directly connected on-link subnet covering p already beats a /0 route
		// whenever its prefix length is >= the tunnel's prefix length (0).
		if r.Destination.Bits() > 0 &&
			(!r.NextHop.IsValid() || r.NextHop.IsUnspecified()) &&
			r.Destination.Overlaps(p) && r.Destination.Bits() <= p.Bits() {
			return true
		}
	}
	return false
}

func isPrivateOrLinkLocalPrefix(p netip.Prefix) bool {
	addr := p.Addr()
	return addr.IsPrivate() || addr.IsLinkLocalUnicast()
}
