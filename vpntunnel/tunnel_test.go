package vpntunnel

import "testing"

// Fixture: real /proc/net/tcp layout. State codes (hex):
//   01 = ESTABLISHED, 06 = TIME_WAIT, 0A = LISTEN.
//   line 0: header (skipped)
//   line 1: ESTABLISHED to 8.8.8.8:443 (01BB) -> WANTED, inode 23456
//   line 2: ESTABLISHED to 192.168.1.1:80 (0050) -> skip (wrong port)
//   line 3: TIME_WAIT (06) to 1.1.1.1:443 -> skip (wrong state)
//   line 4: LISTEN (0A) on local :443, remote 0.0.0.0:0000 -> skip
//           (regression: old code mistook 0A for ESTABLISHED and would
//           have matched this line instead of real DERP sockets)
const procNetTCP = `  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0A00002A:8B0B 08080808:01BB 01 00000000:00000000 00:00000000 00000000     0        0 23456 1 0000000000000000 100 0 0 10 0
   1: 0A00002A:8B0C C0A80101:0050 01 00000000:00000000 00:00000000 00000000     0        0 34567 1 0000000000000000 100 0 0 10 0
   2: 0A00002A:8B0D 01010101:01BB 06 00000000:00000000 00:00000000 00000000     0        0 45678 1 0000000000000000 100 0 0 10 0
   3: 0A00002A:01BB 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 56789 1 0000000000000000 100 0 0 10 0
`

func TestParseDerpInodes(t *testing.T) {
	inodes := parseDerpInodes(procNetTCP)
	if len(inodes) != 1 || !inodes[23456] {
		t.Fatalf("got %v, want exactly {23456}", inodes)
	}
}

func TestParseDerpInodesEmpty(t *testing.T) {
	for _, data := range []string{"", "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"} {
		if got := parseDerpInodes(data); len(got) != 0 {
			t.Fatalf("got %v, want empty", got)
		}
	}
}

func TestParseDerpInodesMalformed(t *testing.T) {
	// Short lines, bad inode, header only — must not panic, must return empty.
	data := "sl\nshort line\n   0: 0100007F:1F90 00000000:0000 01 00000000:00000000 00:00000000 00000000 0 0 notanumber 1 x\n"
	if got := parseDerpInodes(data); len(got) != 0 {
		t.Fatalf("got %v, want empty", got)
	}
}
