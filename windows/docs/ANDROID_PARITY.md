# Android → Windows feature matrix (internal)

Source of truth: the Android sources under `app/` (read only). "Done" = implemented in the Windows code; every Windows-only behaviour is unverified until run on Windows.

| Android feature | Windows | Notes |
|---|---|---|
| Xray profiles (vless/vmess/trojan/ss, JSON), Reality, xhttp | Done | Xray-core 26.10.21 |
| WireGuard | Done | official tunnel service |
| AmneziaWG (real obfuscation) | Done | amneziawg-windows-client 3.1.0, same I1-I5 sanitizer as `ConfigSanitizer.kt` |
| OpenVPN | Done (better than Android) | Android's OpenVPN engine is a stub without a core; Windows runs the official openvpn.exe |
| SlipNet | Partial (upstream) | DNSTT/NoizDNS/VayDNS/SSH/SOCKS5/VLESS/DoH; Slipstream, Snowflake, Naive unsupported by the upstream CLI |
| Proxy mode | Done | SOCKS5 10880 / HTTP 10881 (Xray; SlipNet SOCKS5) |
| VPN mode | Done | native tunnels (WG/AWG/OpenVPN), tun2socks+Wintun (Xray/SlipNet) |
| Clipboard / file / link import, subscription bodies | Done | `.conf`, `.ovpn`, `.json`, `.txt`, `slipnet://` |
| Edit / rename / delete / select / export | Done | |
| Protocol badges, names, summaries | Done | VL/VM/TR/SS/XJ/WG/AW/OV/SN |
| Ping testing, lowest-ping selection, Smart Connect | Done | TCP connect or ICMP; no auto-sort |
| Country flags | Not done | no verified GeoIP source |
| PasarGuard / AliPanel login, account, session, config sync | Done | AliPanel entries WireGuard/AmneziaWG |
| Registration (`/api/register`) | Not done | no verified contract |
| Traffic totals / speeds / duration | Done | SlipNet proxy mode: no counters |
| DNS, MTU | Done | per engine |
| Split tunneling by app | Not done | needs WFP / per-app routing |
| Bypass networks | Done | |
| SNI / transport options | Done (Xray) | via profile fields |
| Logs, redaction, diagnostics | Done | |
| Cleanup / crash recovery | Done | journal + job object |
| Reconnection | Partial | network-change route repair, WG handshake, OpenVPN ping-restart; no auto-restart after crash |
| Notifications | Not done | tray only |
| Tray | Done | |
