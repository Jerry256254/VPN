package main

import (
	"fmt"
	"net"
	"strconv"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

// WgStats mirrors one peer's counters from the device's UAPI dump.
type WgStats struct {
	RxBytes       uint64
	TxBytes       uint64
	LastHandshake int64 // unix seconds, 0 = no handshake yet
}

// resolveEndpoint turns the config hostname into a UDP address, preferring
// IPv4. Falls back to a baked-in WARP IP if DNS is unavailable.
func resolveEndpoint(cfg *WgConfig) (*net.UDPAddr, error) {
	hostport := net.JoinHostPort(cfg.EndpointHost, strconv.Itoa(cfg.EndpointPort))
	if ip, err := net.ResolveIPAddr("ip4", cfg.EndpointHost); err == nil {
		return &net.UDPAddr{IP: ip.IP, Port: cfg.EndpointPort}, nil
	} else {
		fmt.Fprintf(stderr(), "warn: dns for %s failed (%v), using fallback\n", cfg.EndpointHost, err)
		_ = hostport
		return net.ResolveUDPAddr("udp", fallbackEndpoint)
	}
}

// newDevice builds a wireguard-go device on tunDev and configures our single
// WARP peer: default-route everything (v4) through it, keepalive 25s.
func newDevice(tunDev tun.Device, cfg *WgConfig, endpoint *net.UDPAddr) (*device.Device, error) {
	privHex, err := cfg.privateKeyHex()
	if err != nil {
		return nil, err
	}
	peerHex, err := cfg.peerKeyHex()
	if err != nil {
		return nil, err
	}
	logger := device.NewLogger(device.LogLevelError, "[wg] ")
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)
	ipc := fmt.Sprintf(
		"private_key=%s\npublic_key=%s\nendpoint=%s\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n",
		privHex, peerHex, endpoint.String(),
	)
	if err := dev.IpcSet(ipc); err != nil {
		dev.Close()
		return nil, fmt.Errorf("ipc set: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("device up: %w", err)
	}
	return dev, nil
}

// queryStats parses the first peer block out of the UAPI dump.
func queryStats(dev *device.Device) (WgStats, error) {
	var st WgStats
	dump, err := dev.IpcGet()
	if err != nil {
		return st, err
	}
	inPeer := false
	for _, line := range strings.Split(dump, "\n") {
		kv := strings.SplitN(line, "=", 2)
		if len(kv) != 2 {
			continue
		}
		switch kv[0] {
		case "public_key":
			inPeer = true
		case "rx_bytes":
			if inPeer {
				st.RxBytes = parseUint(kv[1])
			}
		case "tx_bytes":
			if inPeer {
				st.TxBytes = parseUint(kv[1])
			}
		case "last_handshake_time_sec":
			if inPeer {
				st.LastHandshake, _ = strconv.ParseInt(kv[1], 10, 64)
			}
		}
	}
	return st, nil
}

func parseUint(s string) uint64 {
	n, _ := strconv.ParseUint(strings.TrimSpace(s), 10, 64)
	return n
}
