package main

import (
	"testing"
	"time"
)

// The kill switch and the ordinary teardown decide "the phone is gone" by the
// same rule; if they drifted, the switch would hold a tunnel the app had
// already been told was dead, or release one it was still showing as held.
func TestAPeerIsStaleOnlyAfterTheWindow(t *testing.T) {
	now := time.Unix(10_000, 0)
	cases := []struct {
		name string
		last int64
		want bool
	}{
		{"never handshaked is not stale (gated behind READY)", 0, false},
		{"a fresh handshake", now.Unix() - 5, false},
		{"exactly at the window", now.Unix() - int64(handshakeStale/time.Second), false},
		{"past the window", now.Unix() - int64(handshakeStale/time.Second) - 1, true},
	}
	for _, c := range cases {
		if got := peerIsStale(c.last, now, handshakeStale); got != c.want {
			t.Errorf("%s: peerIsStale = %v, want %v", c.name, got, c.want)
		}
	}
}
