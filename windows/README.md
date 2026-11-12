# TIREXNET for Windows (x64)

A native Windows desktop client for TIREXNET (.NET 8, WPF). It is a **separate project**: nothing under `app/`,
Gradle, the Android scripts or the Android workflow is used or modified. The Android source was only read to learn the
backend contract, the profile formats and the visual identity.

## What it does

| Area | Status |
|---|---|
| Xray (VLESS, VMess, Trojan, Shadowsocks, Xray JSON; TCP, WS, gRPC, HTTP/2, HTTPUpgrade, **xhttp** incl. path/host/mode/extra, mKCP; TLS and Reality) | Real: official Xray-core binary is started with a generated config (passed on stdin, never written to disk) |
| Proxy mode | Real: SOCKS5 `127.0.0.1:10880` and HTTP `127.0.0.1:10881` (loopback only). **Not** a full-device VPN. Optional Windows system proxy, restored on disconnect |
| VPN mode (Xray) | Real: tun2socks + Wintun adapter `TIREXNET`, routes via two `/1` routes, server and bypass routes pinned to the physical gateway. Needs administrator rights |
| WireGuard | Real: official `wireguard.exe` tunnel service (`/installtunnelservice`). Standard `.conf` files. Needs administrator rights |
| AmneziaWG | Real: official `amneziawg.exe` tunnel service with Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5. Needs administrator rights |
| OpenVPN | Real: official signed `openvpn.exe` supervised through its management interface. Needs administrator rights |
| SlipNet | Real: upstream CLI (DNSTT, NoizDNS, VayDNS, SSH, SOCKS5, VLESS, DoH) as local SOCKS5, optionally full VPN via tun2socks |
| Profiles | Import from clipboard, link, file (`.conf`, `.json`, `.txt`, subscription bodies); rename, edit, export, delete, select. Stored with Windows DPAPI (current user) |
| Account | Login / account / subscription / config sync with the existing backend (see below) |
| DNS, MTU, bypass networks | DNS (Xray resolver, WireGuard `DNS`, tunnel adapter DNS), MTU (tun2socks adapter, WireGuard `MTU`), bypass IPv4 CIDRs in Xray VPN mode |
| Tray | Minimize-to-tray, tray connect/disconnect, clean shutdown |
| Logs | In-app log, diagnostics, secrets redacted |

## Backend integration (only endpoints that the Android app already calls)

Base URL `https://netherrap.extraservices.ir` (HTTPS only), taken from `ApiConfig.kt` / `MainActivity.kt`.

* `POST /api/login` `{username, password, panel}` → `{token}` (`panel` = `pasarguard` | `alipanel`)
* `GET /api/me` (Bearer) → username, `subscription.{username,status,usedTraffic,dataLimit,expire}`
* `GET /api/me/configs` (Bearer) → `configs` | `profiles` | `items`, entries may be strings or objects (`config|uri|raw|content|link`)
* `POST /api/logout` (Bearer)

Same rules as Android: AliPanel entries are classic WireGuard; PasarGuard entries are Xray links. No credentials or
tokens are hard-coded; tokens are stored with DPAPI and redacted in logs.

**Not implemented (no verified contract was needed or available):** account registration (`/api/register`), the server
list cache.

## Engines

All five Android engines have a Windows runtime: **Xray, WireGuard, AmneziaWG, OpenVPN and SlipNet**. Details (runtime, driver,
protocols, modes, permissions, limits) are in [`docs/ENGINES.md`](docs/ENGINES.md); the Android feature comparison is in
[`docs/ANDROID_PARITY.md`](docs/ANDROID_PARITY.md).

Genuine limits: SlipNet's upstream command-line client cannot run Slipstream, Snowflake or NaiveProxy profiles; split tunneling
by application, country flags and notifications are not implemented; the Xray/SlipNet tunnel is IPv4 only.

The UI never shows an engine whose runtime is missing as available (Settings → Engines, Diagnostics). OpenVPN and SlipNet are
optional build-time components: if their download/build fails, the build continues and the verification report says so.

## Windows 7

**Not supported.** .NET 8 requires Windows 10 1607 or newer, and current Wintun / WireGuard builds target Windows 10+.
Nothing in this project claims Windows 7 compatibility.

## Administrator rights

The app starts as a normal user. Proxy mode needs no elevation. VPN mode and WireGuard need it: the app explains why,
asks, and restarts itself through UAC (`runas`). Nothing is installed silently: the Wintun adapter / WireGuard service
exist only while connected and are removed on disconnect. A crash-recovery journal
(`%ProgramData%\TIREXNET\runstate.json`) lets the next start (or the uninstaller, `--cleanup`) remove leftover routes,
the tunnel service and orphaned engine processes. Engine processes are also placed in a Windows Job Object that kills them
if TIREXNET dies.

## Build

CI (`.github/workflows/build-windows.yml`) does everything: tests, publish, engine download + verification, portable ZIP,
Inno Setup installer, silent install/uninstall smoke test. Locally (Windows, .NET 8 SDK):

```powershell
cd windows
dotnet test tests/TIREXNET.Tests
./scripts/make-icon.ps1 -Out src/TIREXNET.App/Assets/tirexnet.ico
dotnet publish src/TIREXNET.App -c Release -r win-x64 --self-contained -o ../dist/stage/app
./scripts/fetch-engines.ps1 -Stage ../dist/stage
```

Engine versions are pinned in `scripts/engines.lock.json`. Binaries are downloaded at build time and never committed.

## Testing limits

CI proves: compilation for x64, parser/validator/config-builder/process-lifecycle tests, package contents, installer
install + uninstall. CI **cannot** prove: a real tunnel to a real server, UAC flows, route/DNS behaviour on a real
network, or any specific protocol against a live server. Those paths are implemented but untested against live servers.

## Audit notes (build-readiness pass)

* Fixed: `SystemProxy.cs` missing `using TIREXNET.Core.Models;`.
* Tests no longer depend on line endings (WireGuard fixtures are LF-joined in code; CRLF variants are tested explicitly).
* `Cmd.RunAsync` / `ManagedProcess` always use `ProcessStartInfo.ArgumentList` (no shell). The argument test now uses `findstr.exe`
  with a literal `a&b` argument and a path containing spaces; `cmd.exe` is only used to produce an exit code / output.
* Async tests use `await` instead of `.Result`; tests run serially because they share process-wide state.
* Added tests: malformed links, IPv6 servers, CRLF WireGuard files, WireGuard address/DNS/AllowedIPs failures, Xray JSON arrays,
  settings and profile-store recovery, safe export names, API client against the real response shapes (fake HTTP handler),
  connection manager failure paths (missing runtime, invalid profile, WireGuard).
* Workflow: explicit Core and WPF Release builds, exit-code checks after every native command, test results always uploaded,
  Android-guard step skipped for manual runs.

Status: these changes were made without access to a Windows machine or to GitHub from the authoring environment, so they are
**unverified until the Windows workflow has run**.

## Engine integrity

`scripts/engines.lock.json` pins every downloaded runtime. `scripts/fetch-engines.ps1` refuses (fails the build) to use a
binary that has neither a pinned SHA-256 nor an upstream checksum. The WireGuard MSI is accepted only with a valid
WireGuard Authenticode signature; the AmneziaWG MSI needs its pinned SHA-256 plus a valid signature; the OpenVPN MSI needs a valid
signature whose signer is OpenVPN; SlipNet is compiled from a pinned commit (verified with `git rev-parse`); Wintun additionally needs a valid signed `wintun.dll`.

* Xray-core v26.10.21: pinned to the SHA-256 published in the official `Xray-windows-64.zip.dgst` of that release.
* tun2socks v2.5.2 publishes no checksum file; the pinned value was measured from the official release asset.

## Look and feel

The interface follows the Android app: same palette (`Palette.kt`), glass cards, blue→cyan primary gradient, pastel
connect orb with per-state colours, drifting ambient background, round protocol badges, switches and segmented
Proxy/VPN control. Desktop layout: navigation sidebar, hero orb, server column, live statistics.
