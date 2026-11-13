# V9.1 status (honest feature matrix)

| Area | State |
|---|---|
| CI test failures (validation order, "runtime is missing" contract, AmneziaWG tests) | Fixed in code; verified only by CI |
| `tirexnet://share/v1/` import (clipboard, paste, file, link click, running instance) | Implemented, byte-compatible with Android `TirexNetShare.kt`; tests use independently generated vectors |
| URI scheme registration | Inno Setup writes `HKLM\Software\Classes\tirexnet`; single-instance hand-over over a current-user named pipe |
| Master DNS / Cotton DNS / StormDNS / Pattern (SNI spoof) / MHR / DNS changer / Soar / encrypted bundles | **Recognised and explained, NOT runnable on Windows** - no runtime is shipped; never imported as a different type |
| Snowflake / NaiveProxy / Slipstream | **Not available** (upstream SlipNet CLI for Windows has no such transport) |
| Custom DNS per profile | Share links carry resolvers/selection/custom settings and keep them in the profile; **no engine applies them yet** |
| System DNS manager / DoH / DNS-leak protection | **Not implemented** |
| OTA updates | Implemented (see UPDATES.md) |
| Application / folder split tunneling (WFP, per-process routing) | **Not implemented.** Only the existing CIDR bypass exists |
