# TIREXNET for Windows - OTA updates

* **Manifest** (`windows-update.json`, https only): `{ "schema":1, "channels": { "stable": {...}, "beta": {...} } }`; each release has
  `version`, `url` (https), `sha256`, `size`, `notes[]`, optional `minVersion`, `requireSignature`. Build it with `scripts/make-update-manifest.ps1`.
* **Check**: at start and every N hours (Settings > Updates, 1-720 h), or "Check now". Stable users only ever see `stable`; Beta users get the newest of both.
  A version that is equal to or older than the installed one is never offered (no downgrade). `minVersion` blocks jumps from too-old builds.
* **Download**: resumable (`.part` + HTTP Range), size and SHA-256 checked; a corrupt file is deleted, never installed.
* **Signature**: when the running build is Authenticode-signed, the update must be validly signed by the *same* publisher. Unsigned builds (CI / development) can only
  rely on SHA-256; a release can force a signature with `requireSignature`. **The SHA-256 comes from the manifest, so the manifest must be served over https from a location you control.**
* **Install**: the installed app folder is copied to `%LOCALAPPDATA%\TIREXNET\updates\backup-<version>`, active connections are closed, then the Inno installer runs silently
  (`/VERYSILENT /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS`). Profiles and settings live in `%APPDATA%\TIREXNET` and are not touched.
* **Rollback**: if the new version is neither the old nor the new version after install, or never reaches a healthy start within 3 launches, the backup is restored
  (a running, locked executable is renamed aside first). Confirmed updates delete the backup.
* **Releasing**: bump `APP_VERSION` in the build workflow (it sets both the assembly and installer versions), build, run the manifest script, upload both files.
* **Not covered**: delta updates; the update server itself; revocation checks (offline-friendly Authenticode validation).
