//go:build 386 || arm

package main

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	wfpPtrSize = 4

	wtFwpByteBlobSize        = 8
	wtFwpmDisplayData0Size   = 8
	wtFwpValue0Size          = 8
	wtFwpmFilterCondition0Sz = 28
	wtFwpmFilter0Size        = 152
	wtFwpmSession0Size       = 48
	wtFwpmProvider0Size      = 40
	wtFwpmSublayer0Size      = 44
)

type fwpmFilter0 struct {
	filterKey           windows.GUID
	displayData         fwpmDisplayData0
	flags               uint32
	providerKey         *windows.GUID
	providerData        fwpByteBlob
	layerKey            windows.GUID
	subLayerKey         windows.GUID
	weight              fwpValue0
	numFilterConditions uint32
	filterCondition     *fwpmFilterCondition0
	action              fwpmAction0
	// MSVC aligns providerContextKey (containing 8-aligned subfields / layout
	// alignment) and filterID (UINT64) to 8 bytes on 32-bit x86/ARM.
	_                  [4]byte
	providerContextKey windows.GUID
	reserved           *windows.GUID
	_                  [4]byte
	filterID           uint64
	effectiveWeight    fwpValue0
}

// Compile-time ABI layout verification matching wireguard-windows kernel checks.
const (
	_ = uint(unsafe.Sizeof(fwpmFilter0{}) - wtFwpmFilter0Size)
	_ = uint(wtFwpmFilter0Size - unsafe.Sizeof(fwpmFilter0{}))
	_ = uint(unsafe.Offsetof(fwpmFilter0{}.providerContextKey) - 112)
	_ = uint(112 - unsafe.Offsetof(fwpmFilter0{}.providerContextKey))
	_ = uint(unsafe.Offsetof(fwpmFilter0{}.filterID) - 136)
	_ = uint(136 - unsafe.Offsetof(fwpmFilter0{}.filterID))
)
