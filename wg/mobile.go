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
	activeMu.Lock()
	defer activeMu.Unlock()

	if active != nil {
		active.Stop()
		active = nil
	}

	endpoint, err := Start(ipcConfig)
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
