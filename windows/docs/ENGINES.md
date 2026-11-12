# TIREXNET for Windows: engines

Five engines, one lifecycle (`IEngine`: `StartAsync` / `StopAsync` / `QueryTotalsAsync` / `Failed`), one factory and capability
table (`EngineCatalog`), one supervisor (`ConnectionManager`: Disconnected → Connecting → Connected → Disconnecting / Error,
cancel while connecting, cleanup on failure), one crash-recovery journal (`RunState` + `Recovery`).

An engine is shown as available only when its runtime file exists (`EngineCatalog.IsRuntimeAvailable`); connecting with a
missing runtime fails immediately with a readable message and touches nothing on the system.

| Engine | Runtime | Driver | Protocols | Proxy mode | VPN mode | Administrator |
|---|---|---|---|---|---|---|
| Xray | Xray-core v26.10.21 | Wintun via tun2socks v2.5.2 (VPN mode) | VLESS, VMess, Trojan, Shadowsocks, Xray JSON; TCP/WS/gRPC/HTTP2/HTTPUpgrade/xhttp/mKCP; TLS, Reality | SOCKS5 127.0.0.1:10880, HTTP 127.0.0.1:10881 | yes (IPv4) | VPN mode only |
| WireGuard | wireguard-windows 0.5.3 tunnel service | Wintun (inside wireguard.exe) | WireGuard | no | yes (native; IPv4/IPv6 from `AllowedIPs`) | yes |
| AmneziaWG | amneziawg-windows-client 3.1.0 tunnel service (`amneziawg.exe`, `awg.exe`, `wintun.dll`) | Wintun | AmneziaWG: Jc, Jmin, Jmax, S1-S4, H1-H4 (numbers or ranges), I1-I5 signature chains | no | yes (native; IPv4/IPv6 from `AllowedIPs`) | yes |
| OpenVPN | Official OpenVPN Community 2.6.19 (`openvpn.exe`, Authenticode checked at every start) | Wintun (forced with `windows-driver wintun`) installed by the official OpenVPN MSI | UDP/TCP, TLS, `tls-crypt`/`tls-auth`, user/password, pushed routes/DNS | no | yes (native, what the server pushes) | yes |
| SlipNet | Upstream `slipnet` Go CLI built from commit `2ed00fd0` | Wintun via tun2socks (VPN mode) | DNSTT, NoizDNS, VayDNS (+ their SSH variants), SSH, SOCKS5, VLESS, DoH | SOCKS5 127.0.0.1:10880 | yes (IPv4) | VPN mode only |

## Xray
* Config built by `XrayConfigBuilder`, passed on stdin. Plaintext VLESS/Trojan to public hosts is refused (Xray 26.x does it too).
* VPN mode: `VpnTunnel` (adapter `TIREXNET`, 10.77.0.2/24, two `/1` routes, `/32` bypass routes for servers and user networks, route journal).
* Statistics through `xray api statsquery`.

## WireGuard / AmneziaWG
* Config written to an administrator-only folder, `… /installtunnelservice`, deleted as soon as the service has read it,
  `… /uninstalltunnelservice` on stop. Service names `WireGuardTunnel$tirexnet-wg` / `AmneziaWGTunnel$tirexnet-awg`.
* Scripts (`PreUp`/`PostUp`/…) are refused. DNS and MTU from Settings override the file.
* AmneziaWG: a `.conf` with any obfuscation parameter is detected as AmneziaWG (never run as plain WireGuard). Validation:
  `Jc` 0-128, `Jmin ≤ Jmax ≤ 1280`, `S1 ≤ 1132`, `S2 ≤ 1188`, `S1 + 56 ≠ S2`, `H1-H4` unique uint32 or ranges, `I1-I5` valid tag chains.
  `I1 = 0` / empty lines (written by some panels to mean "disabled") are dropped, like in the Android app.
* Traffic: adapter statistics.

## OpenVPN
* Profiles are one self-contained text: every key/cert inline. External files (`ca file`, `auth-user-pass file`) are refused.
* Refused for security: `up`, `down`, `route-up`, `plugin`, `script-security`, `tls-verify`, `client-connect`, `log`, `status`, `management*`, `config`, `daemon`, …
  TIREXNET-owned options (`windows-driver`, `disable-dco`, `dev-node`, `verb`, …) are removed from the profile.
* User/password go in an `<auth-user-pass>` block (user on line 1, password on line 2). They are sent through OpenVPN's
  management interface (127.0.0.1, random port, random password read from stdin) and never written to the config file.
* The run config lives in an administrator-only folder and is deleted once the tunnel is up. Stop = `signal SIGTERM` over
  management (OpenVPN removes routes/DNS/adapter itself), then kill after 5 s.
* Status (`state`), byte counters (`bytecount`) and fatal errors come from the management interface. OpenVPN's own
  `ping-restart` reconnect is left alone (logged as state changes).
* The runtime is found in `engines\openvpn\bin`, via `HKLM\SOFTWARE\OpenVPN\exe_path`, or `%ProgramFiles%\OpenVPN\bin`.
  The TIREXNET installer offers to install the official signed MSI; it is checked at build time (Authenticode, publisher OpenVPN),
  and `openvpn.exe` is checked again before every start. If the signature or publisher is wrong, TIREXNET refuses to run it.
* OpenVPN stays installed when TIREXNET is uninstalled (system-wide drivers; remove it in Apps & features).
* IPv6: not blocked by TIREXNET; IPv6 leak protection depends on what the server pushes. Unverified.

## SlipNet
* Links `slipnet://BASE64` (format v18). The upstream CLI provides a local SOCKS5 proxy; VPN mode adds tun2socks + Wintun.
* **Genuine upstream limitation:** the upstream command-line client does not implement Slipstream (`ss`, `slipstream_ssh`), Snowflake or NaiveProxy,
  and `slipnet-enc://` needs a private build key. Such profiles import fine but report why they cannot run on Windows.
* The link (which contains secrets) is passed as a command-line argument, as the upstream CLI requires; it is registered for log redaction.
* Statistics: adapter counters in VPN mode; none in proxy mode (the CLI has no statistics interface).
* License: AGPL-3.0. TIREXNET builds it unmodified from the pinned commit; the source is at https://github.com/anonvector/SlipNet.

## Drivers and safety rules
* Only legitimately signed drivers: Wintun (WireGuard LLC signed) and the OpenVPN project's drivers. TIREXNET never installs a kernel driver itself,
  never changes driver-signature enforcement, never asks to disable antivirus.
* Administrator rights are requested only for modes that need them; the app explains why before the UAC prompt.
* Everything changed on the system is journaled before it is applied; `--cleanup` (also run by the uninstaller) and the next start remove
  leftover routes, tunnel services and the OpenVPN process TIREXNET started (matched by PID *and* start time, so a user's own OpenVPN is never touched).

## Not done / limits (honest list)
* Split tunneling by application (app picker): **not implemented**. Only IPv4 bypass networks (CIDR) exist (Xray/SlipNet VPN mode via routes, OpenVPN via `route … net_gateway`). WireGuard/AmneziaWG use `AllowedIPs` from the profile.
* Tunnel for Xray/SlipNet is IPv4 only.
* No Windows notifications beyond the tray; no country flags (no verified data source); the list is not auto-sorted by ping (the fastest profile is reported and Smart connect selects it).
* No automatic restart after an engine crash: the app reports the error and restores the network.
* Everything that needs Windows (services, adapters, routes, UAC, drivers, MSI) is **unverified** until CI/real devices run it.
