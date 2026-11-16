//go:build amd64 || arm64

package main

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	wfpPtrSize = 8

	wtFwpByteBlobSize        = 16
	wtFwpmDisplayData0Size   = 16
	wtFwpValue0Size          = 16
	wtFwpmFilterCondition0Sz = 40
	wtFwpmFilter0Size        = 200
	wtFwpmSession0Size       = 72
	wtFwpmProvider0Size      = 64
	wtFwpmSublayer0Size      = 72
)

type fwpmFilter0 struct {
	filterKey           windows.GUID
	displayData         fwpmDisplayData0
	flags               uint32
	_                   [4]byte
	providerKey         *windows.GUID
	providerData        fwpByteBlob
	layerKey            windows.GUID
	subLayerKey         windows.GUID
	weight              fwpValue0
	numFilterConditions uint32
	_                   [4]byte
	filterCondition     *fwpmFilterCondition0
	action              fwpmAction0
	// FWPM_FILTER0 is 8-aligned because it carries UINT64 filterID, so
	// providerContextKey after the 20-byte action begins at offset 152.
	_                  [4]byte
	providerContextKey windows.GUID
	reserved           *windows.GUID
	filterID           uint64
	effectiveWeight    fwpValue0
}

// Compile-time ABI layout verification matching wireguard-windows kernel checks.
const (
	_ = uint(unsafe.Sizeof(fwpmFilter0{}) - wtFwpmFilter0Size)
	_ = uint(wtFwpmFilter0Size - unsafe.Sizeof(fwpmFilter0{}))
	_ = uint(unsafe.Offsetof(fwpmFilter0{}.providerContextKey) - 152)
	_ = uint(152 - unsafe.Offsetof(fwpmFilter0{}.providerContextKey))
	_ = uint(unsafe.Offsetof(fwpmFilter0{}.filterID) - 176)
	_ = uint(176 - unsafe.Offsetof(fwpmFilter0{}.filterID))
)
