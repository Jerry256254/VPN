package main

import (
	"context"
	"fmt"
	"io"
	"net/netip"
	"os"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
)

// cmdCheck is the end-to-end self-test: it performs a real WireGuard
// handshake with the WARP relay and pushes real TCP through the tunnel using
// an in-process gVisor netstack TUN - no root and no TUN fd required, so it
// runs on any dev machine with UDP egress to the relay.
func cmdCheck(args []string) error {
	cfgPath, err := configFlag(args)
	if err != nil {
		return err
	}
	cfg, err := loadConfig(cfgPath)
	if err != nil {
		return err
	}
	endpoint, err := resolveEndpoint(cfg)
	if err != nil {
		return fmt.Errorf("resolve endpoint: %w", err)
	}

	v4, err := netip.ParseAddr(stripPrefix(cfg.InterfaceV4))
	if err != nil {
		return fmt.Errorf("bad interface_v4 %q: %w", cfg.InterfaceV4, err)
	}
	addrs := []netip.Addr{v4}
	if v6, err := netip.ParseAddr(stripPrefix(cfg.InterfaceV6)); err == nil && v6.IsValid() {
		addrs = append(addrs, v6)
	}
	dns, _ := netip.ParseAddr("1.1.1.1")

	tunDev, tnet, err := netstack.CreateNetTUN(addrs, []netip.Addr{dns}, cfg.MTU)
	if err != nil {
		return fmt.Errorf("netstack tun: %w", err)
	}
	dev, err := newDevice(tunDev, cfg, endpoint)
	if err != nil {
		return err
	}
	defer dev.Close()

	deadline := time.Now().Add(25 * time.Second)
	var st WgStats
	for {
		st, err = queryStats(dev)
		if err != nil {
			return fmt.Errorf("stats: %w", err)
		}
		if st.LastHandshake != 0 {
			break
		}
		if time.Now().After(deadline) {
			fmt.Println("CHECK-FAIL no handshake within 25s")
			os.Exit(1)
		}
		time.Sleep(500 * time.Millisecond)
	}
	fmt.Fprintf(os.Stderr, "handshake ok (peer time %d), probing TCP via tunnel…\n", st.LastHandshake)

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	conn, err := tnet.DialContext(ctx, "tcp", "1.1.1.1:80")
	if err != nil {
		fmt.Printf("CHECK-FAIL tcp dial via tunnel: %v\n", err)
		os.Exit(1)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := io.WriteString(conn, "GET / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n"); err != nil {
		fmt.Printf("CHECK-FAIL tcp write via tunnel: %v\n", err)
		os.Exit(1)
	}
	head := make([]byte, 15)
	n, err := io.ReadFull(conn, head)
	if err != nil {
		fmt.Printf("CHECK-FAIL tcp read via tunnel: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("CHECK-PASS endpoint=%s http_prefix=%q rx=%d tx=%d\n",
		endpoint.String(), string(head[:n]), st.RxBytes, st.TxBytes)
	return nil
}

func stripPrefix(s string) string {
	for i, c := range s {
		if c == '/' {
			return s[:i]
		}
	}
	return s
}
