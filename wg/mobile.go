package relaywg

import "sync"

// The gomobile-facing surface.
//
// gomobile only exports functions and methods whose parameters and returns are
// a narrow set of types — strings, numbers, booleans, errors, and pointers to
// exported structs from this package. Everything below is written to that rule,
// which is why the session is addressed by an opaque handle instead of by
// returning *Endpoint: a struct with unexported fields and a sync.Mutex does
// not survive the bridge.
//
// Keeping this in its own file also keeps the shape of the real code from being
// bent around the bridge's limitations.

var (
	activeMu sync.Mutex
	active   *Endpoint
)

// StartEndpoint brings up Full Mode from a wireguard-go IPC configuration.
//
// One endpoint at a time, by design: the phone shares one connection, and a
// second endpoint would quietly fight the first for the UDP port. Starting
// while one is running stops the old one first, so a restart after a network
// change is a single call rather than a stop/start the caller has to sequence
// correctly.
func StartEndpoint(ipcConfig string) error {
	return StartEndpointVia(ipcConfig, "")
}

// StartEndpointVia is StartEndpoint with somewhere else to send the traffic it
// forwards: a SOCKS5 address, in practice a VPN client's own local port, or
// empty for the phone's default route.
//
// The reason it is worth a second entry point is in upstream.go. An Android VPN
// captures by UID, so leaving Relay inside the tunnel means the phone cannot
// answer the PC at all, and taking Relay out of it means the PC gets the
// phone's connection rather than the phone's VPN. Going through the VPN's own
// proxy is the only arrangement that gives both.
//
// A separate function rather than a parameter on StartEndpoint because gomobile
// generates the Kotlin binding from the signature, and the old one has callers.
func StartEndpointVia(ipcConfig, upstreamProxy string) error {
	activeMu.Lock()
	defer activeMu.Unlock()

	if active != nil {
		active.Stop()
		active = nil
	}

	endpoint, err := StartVia(ipcConfig, upstreamProxy)
	if err != nil {
		return err
	}
	active = endpoint
	return nil
}

// StopEndpoint tears down whatever is running. Safe to call when nothing is:
// teardown is called from paths that are already unwinding from a failure.
func StopEndpoint() {
	activeMu.Lock()
	defer activeMu.Unlock()
	if active != nil {
		active.Stop()
		active = nil
	}
}

// IsRunning reports whether an endpoint is up, so the Kotlin side can answer
// "is Full Mode actually on?" without keeping its own copy of that state and
// having the two disagree.
func IsRunning() bool {
	activeMu.Lock()
	defer activeMu.Unlock()
	return active != nil
}

// LastHandshakeUnix is when the laptop last completed a handshake, in seconds
// since the epoch; 0 when it never has, and 0 when nothing is running.
//
// The phone's screen is driven by this: it is the only honest signal that a PC
// is really there, since a UDP port answers the same whether or not anyone is
// listening on the far side.
func LastHandshakeUnix() int64 {
	activeMu.Lock()
	defer activeMu.Unlock()
	return active.LastHandshakeUnix()
}

// BytesReceived and BytesSent are the tunnel's counters, from the phone's point
// of view. Exposed as int64 because gomobile carries that across unchanged and
// a byte count on a long session does not fit in an int32.
func BytesReceived() int64 {
	activeMu.Lock()
	defer activeMu.Unlock()
	return active.BytesReceived()
}

func BytesSent() int64 {
	activeMu.Lock()
	defer activeMu.Unlock()
	return active.BytesSent()
}
