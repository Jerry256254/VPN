package main

import (
	"fmt"
	"time"

	"golang.org/x/sys/unix"
)

// recvFdFromAbstractSocket connects to a Linux abstract-namespace Unix domain
// socket (as created by Android's LocalServerSocket, which uses the abstract
// namespace by default) and receives one file descriptor sent via SCM_RIGHTS
// ancillary data. Ported from the retired fdbridge helper.
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
