// testsrv simulates the Kotlin LocalServerSocket side, to verify fdbridge
// end-to-end without needing an Android device: it opens a pipe, sends the
// read-end fd to fdbridge over an abstract unix socket via SCM_RIGHTS, spawns
// fdbridge (which execs into "dummytarget"), and checks dummytarget can read
// the exact bytes written into the pipe by this process.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"time"

	"golang.org/x/sys/unix"
)

func main() {
	sockName := "fdbridge_test_sock"
	fdbridgeBin := os.Args[1]
	dummyTargetBin := os.Args[2]

	listenFd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	must(err)
	must(unix.Bind(listenFd, &unix.SockaddrUnix{Name: "\x00" + sockName}))
	must(unix.Listen(listenFd, 1))

	r, w, err := os.Pipe()
	must(err)

	cmd := exec.Command(fdbridgeBin, sockName, dummyTargetBin, "extra-arg-check")
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	must(cmd.Start())

	connFd, _, err := unix.Accept(listenFd)
	must(err)

	rights := unix.UnixRights(int(r.Fd()))
	must(unix.Sendmsg(connFd, []byte{1}, rights, nil, 0))
	unix.Close(connFd)
	unix.Close(listenFd)

	payload := "hello-from-tun-fd\n"
	_, err = w.WriteString(payload)
	must(err)
	w.Close()

	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	select {
	case err := <-done:
		if err != nil {
			fmt.Println("FAIL: fdbridge/dummytarget exited with error:", err)
			os.Exit(1)
		}
		fmt.Println("PASS: fdbridge successfully passed the fd across exec")
	case <-time.After(5 * time.Second):
		fmt.Println("FAIL: timed out waiting for dummytarget")
		os.Exit(1)
	}
}

func must(err error) {
	if err != nil {
		fmt.Println("FAIL:", err)
		os.Exit(1)
	}
}
