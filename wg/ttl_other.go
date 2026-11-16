//go:build !linux && !android && !darwin

package relaywg

import (
	"syscall"
)

func applySocketTTL(network, address string, raw syscall.RawConn, ttl int) {
	// Socket TTL customization is targeted at Android/Linux outbound tether hops;
	// keep portable compilation clean on Windows.
}
