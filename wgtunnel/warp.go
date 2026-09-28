package main

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"time"

	"golang.org/x/crypto/curve25519"
)

const (
	warpAPI       = "https://api.cloudflareclient.com/v0a4005/reg"
	warpUserAgent = "okhttp/4.12.0"
	endpointHost  = "engage.cloudflareclient.com"
	endpointPort  = 2408
	wgMTU         = 1280
	// Fallback if DNS for the endpoint hostname is unavailable.
	fallbackEndpoint = "162.159.192.1:2408"
)

// WgConfig is the JSON minted by `register` and consumed by `run`/`check`
// (and mirrored by the Kotlin WarpConfig data class - keep field names in sync).
type WgConfig struct {
	PrivateKey    string `json:"private_key"` // base64, 32 bytes
	PublicKey     string `json:"public_key"`  // base64, 32 bytes
	AccountID     string `json:"account_id"`
	AccountToken  string `json:"account_token"`
	PeerPublicKey string `json:"peer_public_key"` // base64
	EndpointHost  string `json:"endpoint_host"`
	EndpointPort  int    `json:"endpoint_port"`
	InterfaceV4   string `json:"interface_v4"`
	InterfaceV6   string `json:"interface_v6"`
	MTU           int    `json:"mtu"`
}

func (c *WgConfig) privateKeyHex() (string, error) {
	return b64ToHex(c.PrivateKey)
}

func (c *WgConfig) peerKeyHex() (string, error) {
	return b64ToHex(c.PeerPublicKey)
}

func b64ToHex(s string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return "", fmt.Errorf("bad base64 key: %w", err)
	}
	return hex.EncodeToString(raw), nil
}

type warpRegRequest struct {
	InstallID string `json:"install_id"`
	Tos       string `json:"tos"`
	Key       string `json:"key"`
	FCMToken  string `json:"fcm_token"`
	Type      string `json:"type"`
	Model     string `json:"model"`
	Name      string `json:"name"`
	Locale    string `json:"locale"`
}

// The API is inconsistent: failures come wrapped as
// {"success":false,"errors":[...]}, but success returns the registration
// object directly (verified against the live API).
type warpErrResponse struct {
	Success bool `json:"success"`
	Errors  []struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"errors"`
}

type warpReg struct {
	ID      string `json:"id"`
	Token   string `json:"token"`
	Key     string `json:"key"`
	Account struct {
		ID string `json:"id"`
	} `json:"account"`
	Config struct {
		Peers []struct {
			PublicKey string `json:"public_key"`
			Endpoint  struct {
				Host string `json:"host"` // e.g. engage.cloudflareclient.com:2408
			} `json:"endpoint"`
		} `json:"peers"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
	} `json:"config"`
}

func cmdRegister() error {
	priv, pub, err := genKeypair()
	if err != nil {
		return fmt.Errorf("keygen: %w", err)
	}
	installID, err := uuid4()
	if err != nil {
		return fmt.Errorf("install id: %w", err)
	}
	reqBody, _ := json.Marshal(warpRegRequest{
		InstallID: installID,
		Tos:       time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		Key:       base64.StdEncoding.EncodeToString(pub[:]),
		FCMToken:  "",
		Type:      "Android",
		Model:     "KucLab",
		Name:      "kuclab-vpn",
		Locale:    "en_US",
	})

	client := &http.Client{Timeout: 25 * time.Second}
	req, err := http.NewRequest("POST", warpAPI, bytes.NewReader(reqBody))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	req.Header.Set("User-Agent", warpUserAgent)

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("warp api unreachable: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return fmt.Errorf("warp api read: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("warp api status %d: %s", resp.StatusCode, truncate(string(body), 200))
	}

	var reg warpReg
	if err := json.Unmarshal(body, &reg); err != nil {
		return fmt.Errorf("warp api bad json: %w", err)
	}
	if reg.ID == "" || reg.Token == "" {
		msg := "unknown error"
		var werr warpErrResponse
		if json.Unmarshal(body, &werr) == nil && len(werr.Errors) > 0 {
			msg = werr.Errors[0].Message
		}
		return fmt.Errorf("warp enroll failed: %s (raw: %s)", msg, truncate(string(body), 300))
	}
	if len(reg.Config.Peers) == 0 || reg.Config.Peers[0].PublicKey == "" {
		return fmt.Errorf("warp enroll failed: no peers in response")
	}

	epHost, epPort := endpointHost, endpointPort
	if h, p, err := netSplitHostPort(reg.Config.Peers[0].Endpoint.Host); err == nil {
		epHost, epPort = h, p
	}

	cfg := WgConfig{
		PrivateKey:    base64.StdEncoding.EncodeToString(priv[:]),
		PublicKey:     base64.StdEncoding.EncodeToString(pub[:]),
		AccountID:     reg.Account.ID,
		AccountToken:  reg.Token,
		PeerPublicKey: reg.Config.Peers[0].PublicKey,
		EndpointHost:  epHost,
		EndpointPort:  epPort,
		InterfaceV4:   reg.Config.Interface.Addresses.V4,
		InterfaceV6:   reg.Config.Interface.Addresses.V6,
		MTU:           wgMTU,
	}
	out, _ := json.Marshal(cfg)
	fmt.Println(string(out))
	return nil
}

// genKeypair makes an X25519 keypair (clamped, RFC 7748) without any new deps.
func genKeypair() (priv, pub [32]byte, err error) {
	if _, err = io.ReadFull(rand.Reader, priv[:]); err != nil {
		return priv, pub, err
	}
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	pubSlice, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return priv, pub, err
	}
	copy(pub[:], pubSlice)
	return priv, pub, nil
}

func uuid4() (string, error) {
	var b [16]byte
	if _, err := io.ReadFull(rand.Reader, b[:]); err != nil {
		return "", err
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}

func netSplitHostPort(hostport string) (string, int, error) {
	h, p, err := net.SplitHostPort(hostport)
	if err != nil {
		return "", 0, err
	}
	port, err := strconv.Atoi(p)
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, fmt.Errorf("bad port %q", p)
	}
	return h, port, nil
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}
