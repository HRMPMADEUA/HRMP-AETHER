# TIREXNET — Integrated Android Project

English-first, dark neon Android VPN app source with Worker login and subscription overview.

## Integrated engines

- **Xray**: CI downloads `libv2ray.aar` automatically. The app has an Android `VpnService` and converts common VLESS, VMess, Trojan and Shadowsocks share links to Xray JSON. Raw Xray JSON is accepted too.
- **WireGuard**: uses the published WG Tunnel backend.
- **AmneziaWG**: uses the WG Tunnel AmneziaWG userspace/JNI backend and config compatibility normalization.

The build is configured for `arm64-v8a` devices.

## Build APK

GitHub Actions workflow: `.github/workflows/build-apk.yml`. It uses Java 21 and Gradle 9.6.1 with Android Gradle Plugin 9 built-in Kotlin support, downloads the Xray AAR, resolves WireGuard/AmneziaWG engines from Maven Central, assembles Debug APK and uploads the `TIREXNET-APK` artifact.

## Important status

- This is a source package, not a prebuilt APK. It has not been built against an Android SDK in this environment. The first GitHub Actions run is needed to catch build/API mismatches.
- The currently deployed Worker exposes `/api/login` and `/api/me`, but the remembered Worker does not yet expose `/api/me/configs`. Login and subscription summary are wired; automatic downloading of raw subscription profiles requires that API endpoint or a subscription URL.
- Xray URI parsing covers common VLESS, VMess, Trojan and Shadowsocks profiles. Custom transport combinations can need adjustments.
- Android allows one active VPN app/tunnel at a time. Don't start Xray while WireGuard or AmneziaWG is active.
- A successful build does not prove that every real-world config connects. Runtime testing on your phone with each protocol is still required.

## Upstream notices

- Xray Android runtime: https://github.com/2dust/AndroidLibXrayLite (LGPL-3.0).
- WireGuard / AmneziaWG backend: https://github.com/wgtunnel/core (MIT plus upstream third-party notices).
