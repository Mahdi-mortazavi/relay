//go:build linux || android || darwin

package relaywg

import (
	"net"
	"syscall"
)

func applySocketTTL(network, address string, raw syscall.RawConn, ttl int) {
	if ttl <= 0 || ttl > 255 {
		return
	}
	isV6 := false
	if host, _, err := net.SplitHostPort(address); err == nil {
		if ip := net.ParseIP(host); ip != nil && ip.To4() == nil {
			isV6 = true
		}
	}
	_ = raw.Control(func(fd uintptr) {
		if isV6 {
			_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IPV6, syscall.IPV6_UNICAST_HOPS, ttl)
		} else {
			_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_TTL, ttl)
		}
	})
}
