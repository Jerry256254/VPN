// Command wgtunnel is the userspace WireGuard core of KucLab VPN.
//
// It talks to Cloudflare's WARP enrollment API (the same API the official
// 1.1.1.1 client uses) to obtain free WireGuard credentials - no user
// account, no e-mail, no personal metadata - and then runs a wireguard-go
// userspace device on the Android VpnService TUN file descriptor, which it
// receives over an abstract Unix socket via SCM_RIGHTS (same technique as
// Clash for Android / Matsuri; the fd-passing code was ported from the
// retired fdbridge helper).
//
// Subcommands:
//
//	register          enroll a new WARP identity, print JSON config to stdout
//	run <sock> --config <path>
//	                receive the TUN fd on abstract socket <sock> and run the
//	                WireGuard device until killed; prints TUNNEL-UP and STATS
//	check --config <path>
//	                end-to-end self-test: handshake + real TCP via an
//	                in-process netstack TUN (no root, no TUN fd needed)
//	recvcheck <sock>  receive one fd on <sock>, print FD-OK, exit (test aid)
//
// All diagnostics go to stderr; stdout carries only machine-readable lines
// (the JSON config, TUNNEL-UP, STATS, FD-OK, CHECK lines) for the Kotlin side.
package main

import (
	"fmt"
	"os"
)

func usage() {
	fmt.Fprintln(os.Stderr, "usage: wgtunnel <register|run|check|recvcheck> [args...]")
	fmt.Fprintln(os.Stderr, "  register")
	fmt.Fprintln(os.Stderr, "  run <abstract-sock-name> --config <path>")
	fmt.Fprintln(os.Stderr, "  check --config <path>")
	fmt.Fprintln(os.Stderr, "  recvcheck <abstract-sock-name>")
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "register":
		err = cmdRegister()
	case "run":
		err = cmdRun(os.Args[2:])
	case "check":
		err = cmdCheck(os.Args[2:])
	case "recvcheck":
		err = cmdRecvCheck(os.Args[2:])
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "wgtunnel:", err)
		os.Exit(1)
	}
}

// configFlag extracts --config <path> from args.
func configFlag(args []string) (string, error) {
	for i := 0; i < len(args)-1; i++ {
		if args[i] == "--config" {
			return args[i+1], nil
		}
	}
	return "", fmt.Errorf("missing --config <path>")
}
