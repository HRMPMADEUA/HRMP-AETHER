# Third-party notices

TIREXNET for Windows downloads, verifies and bundles the following unmodified upstream binaries at build time.
Full license texts are shipped in the `licenses` folder next to the application.

| Component | Version (see `scripts/engines.lock.json`) | License | Upstream |
|---|---|---|---|
| Xray-core | v26.10.21 | MPL-2.0 | https://github.com/XTLS/Xray-core |
| tun2socks | v2.5.2 | MIT | https://github.com/xjasonlyu/tun2socks |
| Wintun | 0.14.1 | Prebuilt binary distribution license of the WireGuard project (redistribution permitted, see the license file in the Wintun archive) | https://www.wintun.net |
| WireGuard for Windows (`wireguard.exe`) | 0.5.3 | MIT | https://www.wireguard.com / https://git.zx2c4.com/wireguard-windows |
| AmneziaWG for Windows (`amneziawg.exe`, `awg.exe`) | 3.1.0 | MIT | https://github.com/amnezia-vpn/amneziawg-windows-client |
| OpenVPN Community (official MSI, installed by the installer; includes its Wintun / ovpn-dco drivers) | 2.6.19 | GPL-2.0 (with OpenSSL exception) and the licenses of its bundled components | https://openvpn.net / https://github.com/OpenVPN/openvpn |
| SlipNet CLI (`slipnet.exe`, built unmodified from the pinned commit) | commit 2ed00fd0 | AGPL-3.0 | https://github.com/anonvector/SlipNet |
| .NET 8 runtime / WPF (self-contained) | 8.0.x | MIT | https://github.com/dotnet/runtime |
| System.Security.Cryptography.ProtectedData | 8.0.0 | MIT | https://github.com/dotnet/runtime |

Xray-core is licensed under the Mozilla Public License 2.0. TIREXNET ships it unmodified and as a separate executable;
its source code is available at the upstream address above.

"WireGuard" and the WireGuard logo are registered trademarks of Jason A. Donenfeld.

SlipNet is licensed under the GNU Affero General Public License v3. TIREXNET ships the unmodified `slipnet.exe` as a separate
executable that it starts as a child process; the complete corresponding source is available at
https://github.com/anonvector/SlipNet (commit `2ed00fd0072ad0b558e30ec6b5e8b30948f98894`, see `scripts/engines.lock.json`).

OpenVPN is licensed under the GNU General Public License v2. TIREXNET does not modify or link it: the installer runs the
official signed MSI, and TIREXNET starts the installed `openvpn.exe` as a separate process.
