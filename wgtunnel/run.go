package main

import (
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"golang.zx2c4.com/wireguard/tun"
)

func cmdRun(args []string) error {
	if len(args) < 1 {
		return fmt.Errorf("usage: wgtunnel run <abstract-sock-name> --config <path>")
	}
	sockName := args[0]
	cfgPath, err := configFlag(args[1:])
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

	fd, err := recvFdFromAbstractSocket(sockName)
	if err != nil {
		return fmt.Errorf("fd handoff: %w", err)
	}
	// The fd is intentionally never closed in this process: it belongs to
	// the Android VpnService and must stay open until the user disconnects.

	// Unmonitored: no netlink, no setMTU - those need CAP_NET_ADMIN which an
	// Android app never has. MTU/addressing are owned by VpnService.Builder.
	tunDev, ifName, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		return fmt.Errorf("tun from fd: %w", err)
	}
	fmt.Fprintf(os.Stderr, "tun device: %s\n", ifName)
	dev, err := newDevice(tunDev, cfg, endpoint)
	if err != nil {
		return err
	}
	defer dev.Close()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-sig
		dev.Close()
		os.Exit(0)
	}()

	fmt.Printf("TUNNEL-UP endpoint=%s\n", endpoint.String())

	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for range tick.C {
		st, err := queryStats(dev)
		if err != nil {
			fmt.Fprintf(os.Stderr, "warn: stats query: %v\n", err)
			continue
		}
		fmt.Printf("STATS rx=%d tx=%d hs=%d\n", st.RxBytes, st.TxBytes, st.LastHandshake)
	}
	return nil
}

func cmdRecvCheck(args []string) error {
	if len(args) < 1 {
		return fmt.Errorf("usage: wgtunnel recvcheck <abstract-sock-name>")
	}
	fd, err := recvFdFromAbstractSocket(args[0])
	if err != nil {
		return err
	}
	fmt.Printf("FD-OK fd=%d\n", fd)
	return nil
}

func loadConfig(path string) (*WgConfig, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	var cfg WgConfig
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	if cfg.PrivateKey == "" || cfg.PeerPublicKey == "" {
		return nil, fmt.Errorf("config missing keys")
	}
	if cfg.EndpointHost == "" {
		cfg.EndpointHost = endpointHost
	}
	if cfg.EndpointPort == 0 {
		cfg.EndpointPort = endpointPort
	}
	if cfg.MTU == 0 {
		cfg.MTU = wgMTU
	}
	return &cfg, nil
}

func stderr() *os.File { return os.Stderr }
