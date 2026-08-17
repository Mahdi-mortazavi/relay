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
// That is the whole of the "YouTube works but Telegram does not" report. A
// browser opens connections constantly, so it appears to work within seconds.
// Telegram holds long-lived connections to its data centres, so it stays
// outside the tunnel for as long as it is running -- measured on hardware:
//
//	before  Telegram -> 192.168.1.13 (Wi-Fi), tunnel ignored
//	after   Telegram -> 10.13.37.2   (tunnel), every connection
//
// where "after" was nothing more than restarting the application. So the fix is
// to make the restart unnecessary: close those connections and let each program
// reconnect through the route that now exists. Applications reconnect from a
// dropped TCP connection routinely -- it is the same thing they see when a
// Wi-Fi network changes.
//
// Deliberately narrow. Only established connections that would now take a
// different route are closed; loopback and the local subnet are left alone,
// because a VPN that kills your printer and your dev server is worse than one
// that misses Telegram.
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
// network byte order, which is why the ports are byte-swapped below.
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

// resetForeignConnections closes established IPv4 connections that did not come
// from the tunnel, so the programs holding them reconnect through it.
//
// tunnelAddr is this end of the tunnel; anything already sourced there is
// already correct. Returns how many were closed, for the log.
func resetForeignConnections(tunnelAddr netip.Addr) int {
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

		// Already ours.
		if local == tunnelAddr {
			continue
		}
		// Never touch the machine talking to itself, or to its own network:
		// loopback, link-local and private destinations keep working exactly as
		// they did, which is what stops this from breaking a local printer, a
		// dev server, or the router's admin page.
		if !remote.IsValid() || remote.IsLoopback() || remote.IsLinkLocalUnicast() ||
			remote.IsPrivate() || remote.IsMulticast() || remote.IsUnspecified() {
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
		// A failure here is ordinary: the connection may have closed on its own
		// between reading the table and acting on it, and some belong to
		// processes this one may not touch. Neither is worth failing the tunnel
		// over -- the worst case is the old behaviour, which is what shipped.
	}
	return closed
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
