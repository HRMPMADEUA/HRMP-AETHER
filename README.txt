TIREXNET — SlipNet runtime inside the app
========================================

This patch keeps the TIREXNET home screen, server list, connect/disconnect button,
and settings dialog as the only user-facing UI. SlipNet remains the actual VPN
engine and profile database. Xray, WireGuard, Amnezia and the other engines are
not removed or replaced.

APPLY
-----
1. Extract this ZIP.
2. Copy BOTH files below to the ROOT of your HRMP-AETHER repository checkout:
     apply_tirexnet_inapp_slipnet.py
     TirexSlipNetRuntime.kt
3. In Termux, from the repository root, run:
     python3 apply_tirexnet_inapp_slipnet.py
4. Review the changed files with:
     git diff --check
     git status --short
5. Commit/push the changes and check the GitHub Actions build before installing.

The script creates *.inapp-slipnet.bak backups before changing the files. It
validates all expected source anchors and the Android manifest before writing;
it stops instead of partially applying if the checkout does not match the
expected branch version.

WHAT IT CHANGES
---------------
- Adds an in-process Hilt bridge to SlipNet's ConfigImporter, ProfileRepository,
  PreferencesDataStore and VpnConnectionManager.
- Imports slipnet://, slipnet-enc://, slipnet-bundle-enc:// and vless:// profiles
  through SlipNet's real importer/database.
- Handles encrypted bundle passwords and profile lock passwords inside TIREXNET.
- Starts and stops SlipNetVpnService from TIREXNET's own Connect button.
- Observes SlipNet's real connection state for TIREXNET's status display.
- Applies the global SlipNet settings currently exposed by TIREXNET's settings
  dialog to SlipNet's actual DataStore, and uses profile defaults for supported
  protocol fields while retaining explicit values already carried by a profile.
- Makes the upstream SlipNet Compose Activity private and removes its external
  share-link routes. SlipNet service notifications open TIREXNET instead.
- Updates the CI staging checks to verify the native in-app route.

The Android VPN permission sheet may still appear the first time: that is the
Android system permission UI, not a separate SlipNet screen.

IMPORTANT
---------
This package is a source patch, not a compiled APK. The GitHub Actions build
must complete successfully before installing the APK. Xray source and its
native library integration are untouched.
