//go:build !amd64 && !arm64

package main

import (
	"errors"
	"net/netip"
)

// enableLeakProtection is not implemented on 32-bit.
//
// FWPM_FILTER0 needs a different layout correction there, and a struct layout
// that has not been checked against a real kernel is not something to hand a
// driver for the sake of a build the README already describes as "only if you
// know you need it".
//
// Returning an error rather than pretending is the whole point: the caller
// reports LEAK-PROTECTION-FAILED and the app says so, which is what stops
// someone believing they are protected when they are not.
func enableLeakProtection(resolvers []netip.Addr) (func(), error) {
	return nil, errors.New("leak protection is only implemented on 64-bit builds")
}
