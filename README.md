# VPN Pocket

App proxy cá nhân Android của Henrry — 1 nút connect, VpnService + TUN route traffic qua proxy.

- **Mode A** (mặc định, free): qua VM proxy → IP xoay. TCP-only.
- **Mode B** (trả tiền theo GB): ProxyScrape Residential (HTTP/SOCKS5 + UDP) — đi trực tiếp.

Spec/plan/sơ đồ final: `~/workspace/user/files/vpn-pocket-{spec,plan,sodo}.{md,html}`
Thiết kế tunnel: `~/workspace/user/files/vpn-pocket-tunnel-design.md`
Workflow build: `~/workspace/english-lab/vpn-build-workflow.md`

## Build

CI tự build trên GitHub Actions (`.github/workflows/build.yml`):
1. `gomobile bind` → `vpntunnel.aar` (arm64)
2. Gradle → debug APK

APK release ở tab Releases (prerelease).
