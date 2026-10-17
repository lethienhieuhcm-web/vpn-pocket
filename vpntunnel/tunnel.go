// Package vpntunnel exposes the tailcat tunnel to the VPN Pocket Android app.
//
// P1: stub compilable để CI ra được AAR — P2 thay bằng tailcat.Client thật.
// Thiết kế chi tiết: ~/workspace/user/files/vpn-pocket-tunnel-design.md
// Lưu ý gomobile: KHÔNG dùng context.Context (không bind được), timeout để hằng số nội bộ.
package vpntunnel

import "errors"

// Protector is implemented by the Android app (VpnService.protect).
type Protector interface {
	Protect(fd int) bool
}

// Tunnel wraps the (P2: tailcat) client.
type Tunnel struct {
	serveAddr string
	protector Protector
}

// NewTunnel creates a tunnel. serveAddr is the tailcat address from the QR code.
func NewTunnel(serveAddr string, p Protector) *Tunnel {
	return &Tunnel{serveAddr: serveAddr, protector: p}
}

// Connect establishes the tunnel. P2: real tailcat client + protect(fd).
func (t *Tunnel) Connect() error {
	if t.serveAddr == "" {
		return errors.New("vpntunnel: empty serve address")
	}
	return nil
}

// DialTCP opens a TCP stream through the tunnel. P2: via tailcat DialTCPPort.
func (t *Tunnel) DialTCP(host string, port int) (*Conn, error) {
	return nil, errors.New("vpntunnel: not implemented (P2)")
}

// ProtectScan re-scans /proc for DERP sockets and protects them. P2.
func (t *Tunnel) ProtectScan() int { return 0 }

// Close shuts the tunnel down.
func (t *Tunnel) Close() error { return nil }

// Conn is a gomobile-compatible connection wrapper.
type Conn struct{}

// Read reads up to max bytes.
func (c *Conn) Read(max int) ([]byte, error) {
	return nil, errors.New("vpntunnel: not implemented (P2)")
}

// Write writes p.
func (c *Conn) Write(p []byte) (int, error) {
	return 0, errors.New("vpntunnel: not implemented (P2)")
}

// Close closes the connection.
func (c *Conn) Close() error { return nil }
