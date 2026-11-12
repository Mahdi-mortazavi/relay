//go:build windows

package relaywg

import (
	"errors"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/conn"
)

// resilientBind wraps conn.NewDefaultBind() on Windows to absorb WSAECONNRESET
// (ICMP Port Unreachable after a peer NAT rebinding or socket close), which
// otherwise causes wireguard-go's RoutineReceiveIncoming to exit permanently.
type resilientBind struct {
	conn.Bind
}

func newResilientBind() conn.Bind {
	return &resilientBind{Bind: conn.NewDefaultBind()}
}

func (b *resilientBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	fns, actualPort, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	wrapped := make([]conn.ReceiveFunc, len(fns))
	for i, fn := range fns {
		orig := fn
		wrapped[i] = func(bufs [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
			for {
				n, rerr := orig(bufs, sizes, eps)
				if rerr != nil && errors.Is(rerr, windows.WSAECONNRESET) {
					continue
				}
				return n, rerr
			}
		}
	}
	return wrapped, actualPort, nil
}
