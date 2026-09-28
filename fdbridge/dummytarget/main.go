// dummytarget stands in for tun2socks in the fdbridge end-to-end test.
// It parses --device fd://N (exactly like real tun2socks would) and reads
// from that fd to prove it was inherited correctly across fdbridge's exec.
package main

import (
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
)

func main() {
	if os.Args[1] != "--device" {
		fmt.Println("FAIL: expected --device as first arg, got", os.Args[1])
		os.Exit(1)
	}
	fdArg := os.Args[2] // fd://N
	if !strings.HasPrefix(fdArg, "fd://") {
		fmt.Println("FAIL: bad device arg", fdArg)
		os.Exit(1)
	}
	if os.Args[3] != "extra-arg-check" {
		fmt.Println("FAIL: extra args were not passed through by fdbridge")
		os.Exit(1)
	}
	n, err := strconv.Atoi(strings.TrimPrefix(fdArg, "fd://"))
	if err != nil {
		fmt.Println("FAIL: bad fd number:", err)
		os.Exit(1)
	}

	f := os.NewFile(uintptr(n), "inherited-fd")
	data, err := io.ReadAll(f)
	if err != nil {
		fmt.Println("FAIL: read from inherited fd:", err)
		os.Exit(1)
	}
	if string(data) != "hello-from-tun-fd\n" {
		fmt.Printf("FAIL: unexpected payload: %q\n", string(data))
		os.Exit(1)
	}
	fmt.Println("dummytarget: got correct payload via inherited fd")
}
