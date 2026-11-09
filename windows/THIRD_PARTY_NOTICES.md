# Third-party notices

TIREXNET for Windows downloads, verifies and bundles the following unmodified upstream binaries at build time.
Full license texts are shipped in the `licenses` folder next to the application.

| Component | Version (see `scripts/engines.lock.json`) | License | Upstream |
|---|---|---|---|
| Xray-core | v1.8.24 | MPL-2.0 | https://github.com/XTLS/Xray-core |
| tun2socks | v2.5.2 | MIT | https://github.com/xjasonlyu/tun2socks |
| Wintun | 0.14.1 | Prebuilt binary distribution license of the WireGuard project (redistribution permitted, see the license file in the Wintun archive) | https://www.wintun.net |
| WireGuard for Windows (`wireguard.exe`) | 0.5.3 | MIT | https://www.wireguard.com / https://git.zx2c4.com/wireguard-windows |
| .NET 8 runtime / WPF (self-contained) | 8.0.x | MIT | https://github.com/dotnet/runtime |
| System.Security.Cryptography.ProtectedData | 8.0.0 | MIT | https://github.com/dotnet/runtime |

Xray-core is licensed under the Mozilla Public License 2.0. TIREXNET ships it unmodified and as a separate executable;
its source code is available at the upstream address above.

"WireGuard" and the WireGuard logo are registered trademarks of Jason A. Donenfeld.
