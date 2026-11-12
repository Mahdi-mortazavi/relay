package main

import (
	"encoding/binary"
	"fmt"
	"net/netip"
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Closing the connections that were open before the tunnel existed.
//
// Windows binds a TCP connection to a source address when it is created and
// never moves it. Bringing a tunnel up therefore changes nothing for traffic
// that is already flowing: new connections take the tunnel's default route,
// while every existing one keeps leaving by the adapter it was born on.
//
// Previously this ran *after* waitForHandshake (up to a 20s leak window) and
// unconditionally exempted all RFC1918 private destinations even when LAN access
// was disallowed. Now this runs immediately after WFP leak protection is armed,
// and respects allowLAN and excludePrefixes.
//
// Note on IPv6 TCP and pre-existing UDP/QUIC sockets: iphlpapi's SetTcpEntry is
// IPv4-only (Win32 exposes no SetTcp6Entry), and UDP is connectionless at the
// TCB table level. Committing the blanket ALE connect/recv-accept filters in
// wfp_windows.go triggers Windows BFE ALE reauthorization on those existing
// sockets so they cannot keep leaking either.
const (
	tcpTableOwnerPidAll = 5
	tcpStateEstablished = 5
	tcpStateDeleteTCB   = 12
)

var (
	iphlpapi                = windows.NewLazySystemDLL("iphlpapi.dll")
	procGetExtendedTcpTable = iphlpapi.NewProc("GetExtendedTcpTable")
	procSetTcpEntry         = iphlpapi.NewProc("SetTcpEntry")
)

// mibTcpRowOwnerPid mirrors MIB_TCPROW_OWNER_PID. Addresses and ports are in
// network byte order, which is why addrOf reverses the DWORD below.
type mibTcpRowOwnerPid struct {
	State      uint32
	LocalAddr  uint32
	LocalPort  uint32
	RemoteAddr uint32
	RemotePort uint32
	OwningPid  uint32
}

// mibTcpRow is what SetTcpEntry takes: the same five fields without the PID.
type mibTcpRow struct {
	State      uint32
	LocalAddr  uint32
	LocalPort  uint32
	RemoteAddr uint32
	RemotePort uint32
}

// resetForeignConnections closes established IPv4 TCP connections that did not
// originate on the tunnel interface, so owners reconnect inside the tunnel (or
// get blocked by WFP).
func resetForeignConnections(tunnelAddr, peerAddr netip.Addr, excludePrefixes []netip.Prefix, allowLAN bool) int {
	table, err := tcpTable()
	if err != nil {
		fmt.Fprintf(os.Stderr, "could not read the TCP table: %v\n", err)
		return 0
	}

	closed := 0
	for _, row := range table {
		if row.State != tcpStateEstablished {
			continue
		}
		local := addrOf(row.LocalAddr)
		remote := addrOf(row.RemoteAddr)
		if !shouldResetForeignConnection(local, remote, tunnelAddr, peerAddr, excludePrefixes, allowLAN) {
			continue
		}

		entry := mibTcpRow{
			State:      tcpStateDeleteTCB,
			LocalAddr:  row.LocalAddr,
			LocalPort:  row.LocalPort,
			RemoteAddr: row.RemoteAddr,
			RemotePort: row.RemotePort,
		}
		ret, _, _ := procSetTcpEntry.Call(uintptr(unsafe.Pointer(&entry)))
		if ret == 0 {
			closed++
		}
	}
	return closed
}

func shouldResetForeignConnection(
	local, remote, tunnelAddr, peerAddr netip.Addr,
	excludePrefixes []netip.Prefix,
	allowLAN bool,
) bool {
	if tunnelAddr.IsValid() && local == tunnelAddr {
		return false
	}
	if !remote.IsValid() || remote.IsLoopback() || remote.IsLinkLocalUnicast() ||
		remote.IsMulticast() || remote.IsUnspecified() {
		return false
	}
	if peerAddr.IsValid() && remote == peerAddr {
		return false
	}
	if allowLAN && remote.IsPrivate() {
		return false
	}
	for _, p := range excludePrefixes {
		if p.Contains(remote) {
			return false
		}
	}
	return true
}

func tcpTable() ([]mibTcpRowOwnerPid, error) {
	var size uint32
	// First call sizes the buffer; ERROR_INSUFFICIENT_BUFFER is expected.
	procGetExtendedTcpTable.Call(0, uintptr(unsafe.Pointer(&size)), 0,
		uintptr(windows.AF_INET), tcpTableOwnerPidAll, 0)
	if size == 0 {
		return nil, fmt.Errorf("the TCP table reported no size")
	}

	buf := make([]byte, size)
	ret, _, _ := procGetExtendedTcpTable.Call(
		uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)), 0,
		uintptr(windows.AF_INET), tcpTableOwnerPidAll, 0)
	if ret != 0 {
		return nil, fmt.Errorf("GetExtendedTcpTable: %d", ret)
	}

	count := binary.LittleEndian.Uint32(buf[:4])
	rowSize := unsafe.Sizeof(mibTcpRowOwnerPid{})
	rows := make([]mibTcpRowOwnerPid, 0, count)
	for i := uint32(0); i < count; i++ {
		offset := uintptr(4) + uintptr(i)*rowSize
		if offset+rowSize > uintptr(len(buf)) {
			break
		}
		rows = append(rows, *(*mibTcpRowOwnerPid)(unsafe.Pointer(&buf[offset])))
	}
	return rows, nil
}

// addrOf turns the table's network-order IPv4 address into a netip.Addr.
func addrOf(raw uint32) netip.Addr {
	var b [4]byte
	binary.LittleEndian.PutUint32(b[:], raw)
	return netip.AddrFrom4(b)
}
