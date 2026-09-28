// fdbridge receives an open file descriptor (the Android VpnService TUN fd)
// from the Kotlin process over an abstract-namespace Unix domain socket using
// SCM_RIGHTS ancillary data, then execs into the real tun2socks binary with
// that fd wired in as its --device argument.
//
// Usage: fdbridge <abstract-socket-name> <path-to-tun2socks> [extra tun2socks args...]
//
// The Kotlin side is expected to:
//  1. open a LocalServerSocket(name) BEFORE spawning this process
//  2. accept() the incoming connection from this process
//  3. setFileDescriptorsForSend([tunFd]) then write one byte to the socket
//
// No cgo / NDK is required: golang.org/x/sys/unix implements sendmsg/recvmsg
// and SCM_RIGHTS parsing in pure Go using raw syscalls.
package main

import (
	"fmt"
	"os"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: fdbridge <abstract-socket-name> <tun2socks-path> [args...]")
		os.Exit(2)
	}
	sockName := os.Args[1]
	tun2socksPath := os.Args[2]
	extraArgs := os.Args[3:]

	fd, err := recvFdFromAbstractSocket(sockName)
	if err != nil {
		fmt.Fprintln(os.Stderr, "fdbridge: failed to receive tun fd:", err)
		os.Exit(1)
	}

	// Received fds are not close-on-exec by default, so they survive execve.
	deviceArg := fmt.Sprintf("fd://%d", fd)
	args := append([]string{tun2socksPath, "--device", deviceArg}, extraArgs...)

	if err := syscall.Exec(tun2socksPath, args, os.Environ()); err != nil {
		fmt.Fprintln(os.Stderr, "fdbridge: exec tun2socks failed:", err)
		os.Exit(1)
	}
}

// recvFdFromAbstractSocket connects to a Linux abstract-namespace unix domain
// socket (as created by Android's LocalServerSocket/LocalSocket, which use
// the abstract namespace by default) and receives one file descriptor sent
// via SCM_RIGHTS ancillary data.
func recvFdFromAbstractSocket(name string) (int, error) {
	sock, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return -1, fmt.Errorf("socket: %w", err)
	}
	defer unix.Close(sock)

	addr := &unix.SockaddrUnix{Name: "\x00" + name}

	// The Kotlin-side LocalServerSocket may not have called accept() yet
	// immediately after this process is spawned; retry briefly.
	var lastErr error
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		lastErr = unix.Connect(sock, addr)
		if lastErr == nil {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if lastErr != nil {
		return -1, fmt.Errorf("connect: %w", lastErr)
	}

	buf := make([]byte, 4)
	oob := make([]byte, unix.CmsgSpace(4)) // room for one int fd

	n, oobn, _, _, err := unix.Recvmsg(sock, buf, oob, 0)
	if err != nil {
		return -1, fmt.Errorf("recvmsg: %w", err)
	}
	if n == 0 {
		return -1, fmt.Errorf("recvmsg: peer closed before sending trigger byte")
	}

	cmsgs, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return -1, fmt.Errorf("parse cmsg: %w", err)
	}
	for _, c := range cmsgs {
		fds, err := unix.ParseUnixRights(&c)
		if err != nil {
			continue
		}
		if len(fds) > 0 {
			return fds[0], nil
		}
	}
	return -1, fmt.Errorf("no fd received in ancillary data")
}
