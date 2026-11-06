# CHANGELOG

- Added ProfileSettingsStore with stable per-profile IDs, schema versioning, resolver selection and settings migration on raw-config edits.
- Added active-network DNS probes for UDP/TCP and HTTPS DNS endpoints, plus explicit DoT probe.
- Added conservative field decoder and editor handoff for VLESS, VMess, Trojan, Shadowsocks, WireGuard, AmneziaWG, MasterDNS, StormDNS, CottenDNS, MHRV, DNS Changer and SlipNet v30 known layouts.
- Added per-profile Resolver handoff to Xray, WireGuard/AmneziaWG, StormDNS, MasterDNS, CottenDNS and SlipNet's ServerProfile model.
- Added local profile duplication for supported formats.
- No Xray, WireGuard, AmneziaWG, MasterDNS, CottenDNS or SlipNet engine was removed.
- Build status: NOT RUN in this environment.
