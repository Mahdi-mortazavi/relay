//go:build !windows

package relaywg

import "golang.zx2c4.com/wireguard/conn"

func newResilientBind() conn.Bind {
	return conn.NewDefaultBind()
}
