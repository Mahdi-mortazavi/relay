//go:build tools

// gomobile refuses to bind a module that does not have golang.org/x/mobile in
// its dependency graph, but nothing in the library imports it — the bridge is
// generated, not called. This file exists only to put it in go.mod, and the
// build tag keeps it out of every real build.
package relaywg

import _ "golang.org/x/mobile/bind"
