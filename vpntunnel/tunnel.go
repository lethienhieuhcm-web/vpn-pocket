// Package vpntunnel exposes the tailcat tunnel to the VPN Pocket Android app.
//
// Architecture (see ~/workspace/user/files/vpn-pocket-tunnel-design.md):
//   - Mode A: TCP dialed through the tailcat tunnel, then HTTP CONNECT to the
//     VM proxy (proxy.py:8899). UDP is not supported in Mode A (VM blocks it).
//   - DERP sockets must be protect()ed or packets loop back into the TUN.
//     tailcat exposes no socket-creation hook, so after Connect() we scan
//     /proc/self/net/tcp{,6} for ESTABLISHED sockets to remote port 443
//     (DERP is HTTPS) and protect them; a watchdog re-scans every 30s.
//
// gomobile constraints: no context.Context in the API, no net.Conn exposed
// directly — everything goes through simple wrapper types.
package vpntunnel

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/types/key"
)

// dialTimeout bounds tunnel dial + CONNECT handshake.
const dialTimeout = 30 * time.Second

// derpPort is the remote port DERP servers listen on (HTTPS).
const derpPort = "01BB" // 443 in hex, as seen in /proc/net/tcp

// Protector is implemented by the Android app (VpnService.protect).
type Protector interface {
	Protect(fd int) bool
}

// GenerateKey creates a new tailcat client private key, serialized as text.
// Call once on first run; store the result in EncryptedSharedPreferences and
// pass it to NewTunnel on every launch.
func GenerateKey() (string, error) {
	priv := key.NewNode()
	b, err := priv.MarshalText()
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// Tunnel wraps a tailcat client. Safe for concurrent use.
type Tunnel struct {
	mu        sync.Mutex
	client    *tailcat.Client
	protector Protector
	protected map[int64]bool // inodes already protect()ed
	closed    bool
}

// NewTunnel creates a tunnel.
// serveAddr: tailcat address string from the QR code (validated).
// keyStr:    GenerateKey() output previously stored by the app.
// p:         implemented by the app to protect tunnel sockets.
func NewTunnel(serveAddr, keyStr string, p Protector) (*Tunnel, error) {
	if serveAddr == "" {
		return nil, errors.New("vpntunnel: empty serve address")
	}
	if _, err := tailcat.ParseAddr(tailcat.Addr(serveAddr)); err != nil {
		return nil, fmt.Errorf("vpntunnel: bad serve address: %w", err)
	}
	var priv key.NodePrivate
	if err := priv.UnmarshalText([]byte(keyStr)); err != nil {
		return nil, fmt.Errorf("vpntunnel: bad client key: %w", err)
	}
	t := &Tunnel{
		client: &tailcat.Client{
			Server: tailcat.Addr(serveAddr),
			Key:    priv,
			Logf:   func(string, ...any) {},
		},
		protector: p,
		protected: map[int64]bool{},
	}
	return t, nil
}

// Connect starts the tunnel (DERP connection + server handshake) and
// protects the DERP sockets so their packets bypass the VPN interface.
func (t *Tunnel) Connect() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return errors.New("vpntunnel: tunnel closed")
	}
	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	defer cancel()
	// Ping starts the client if needed and performs the meow handshake
	// with the server through DERP. Its internal timeout is 10s.
	if _, err := t.client.Ping(ctx); err != nil {
		return fmt.Errorf("vpntunnel: connect: %w", err)
	}
	t.protectScanLocked()
	return nil
}

// DialTCP opens a TCP stream through the tunnel to host:port (Mode A).
// It dials the VM proxy port through the tunnel and issues HTTP CONNECT.
func (t *Tunnel) DialTCP(host string, port int) (*Conn, error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("vpntunnel: tunnel closed")
	}
	client := t.client
	t.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	defer cancel()
	raw, err := client.DialTCPPort(ctx, 8899)
	if err != nil {
		return nil, fmt.Errorf("vpntunnel: dial: %w", err)
	}
	// HTTP CONNECT handshake with the VM proxy.
	raw.SetDeadline(time.Now().Add(dialTimeout))
	req := fmt.Sprintf("CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n", host, port, host, port)
	if _, err := raw.Write([]byte(req)); err != nil {
		raw.Close()
		return nil, fmt.Errorf("vpntunnel: CONNECT write: %w", err)
	}
	br := bufio.NewReader(raw)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		raw.Close()
		return nil, fmt.Errorf("vpntunnel: CONNECT read: %w", err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		raw.Close()
		return nil, fmt.Errorf("vpntunnel: CONNECT status %s", resp.Status)
	}
	raw.SetDeadline(time.Time{}) // blocking mode for the data phase
	return &Conn{conn: raw, br: br}, nil
}

// ProtectScan re-scans /proc for DERP sockets and protects any new ones.
// Called by the watchdog every 30s (DERP reconnects use new sockets).
// Returns the number of newly protected sockets.
func (t *Tunnel) ProtectScan() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.protectScanLocked()
}

func (t *Tunnel) protectScanLocked() int {
	if t.protector == nil {
		return 0
	}
	count := 0
	for _, s := range scanDerpSockets() {
		if t.protected[s.inode] {
			continue
		}
		if t.protector.Protect(s.fd) {
			t.protected[s.inode] = true
			count++
		}
	}
	return count
}

// Close shuts the tunnel down.
func (t *Tunnel) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return nil
	}
	t.closed = true
	return t.client.Close()
}

// Conn is a gomobile-compatible TCP connection through the tunnel.
type Conn struct {
	conn net.Conn
	br   *bufio.Reader
}

// Read reads up to max bytes (blocking). Returns (nil, io.EOF) at end of stream.
func (c *Conn) Read(max int) ([]byte, error) {
	if max <= 0 {
		max = 32 * 1024
	}
	if max > 64*1024 {
		max = 64 * 1024
	}
	buf := make([]byte, max)
	n, err := c.br.Read(buf)
	if n > 0 {
		return buf[:n], nil
	}
	return nil, err
}

// Write writes p, returning bytes written.
func (c *Conn) Write(p []byte) (int, error) {
	return c.conn.Write(p)
}

// Close closes the connection.
func (c *Conn) Close() error {
	return c.conn.Close()
}

// derpSocket is an ESTABLISHED socket to remote port 443 owned by this process.
type derpSocket struct {
	fd    int
	inode int64
}

// scanDerpSockets finds ESTABLISHED TCP sockets to remote port 443
// (DERP is HTTPS) in /proc/self/net/tcp{,6} and maps them to fds.
func scanDerpSockets() []derpSocket {
	inodes := map[int64]bool{}
	for _, path := range []string{"/proc/self/net/tcp", "/proc/self/net/tcp6"} {
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(data), "\n") {
			f := strings.Fields(line)
			// sl local_address rem_address st ... inode
			if len(f) < 10 || f[0] == "sl" {
				continue
			}
			rem, st := f[2], f[3]
			if st != "0A" { // ESTABLISHED
				continue
			}
			parts := strings.Split(rem, ":")
			if len(parts) != 2 || parts[1] != derpPort {
				continue
			}
			inode, err := strconv.ParseInt(f[9], 10, 64)
			if err != nil {
				continue
			}
			inodes[inode] = true
		}
	}
	if len(inodes) == 0 {
		return nil
	}
	var out []derpSocket
	fds, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return nil
	}
	for _, e := range fds {
		target, err := os.Readlink("/proc/self/fd/" + e.Name())
		if err != nil {
			continue
		}
		// target looks like "socket:[12345]"
		if !strings.HasPrefix(target, "socket:[") || !strings.HasSuffix(target, "]") {
			continue
		}
		inode, err := strconv.ParseInt(target[8:len(target)-1], 10, 64)
		if err != nil || !inodes[inode] {
			continue
		}
		fd, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		out = append(out, derpSocket{fd: fd, inode: inode})
	}
	return out
}
