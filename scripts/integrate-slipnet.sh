#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="${1:-$ROOT/.native-src/slipnet}"
APP="$ROOT/app"

[[ -d "$UPSTREAM/app/src/main/java/app/slipnet" ]] || {
  echo "SlipNet source checkout not found at: $UPSTREAM" >&2
  exit 2
}
[[ -d "$UPSTREAM/app/src/full/jniLibs/arm64-v8a" ]] || {
  echo "SlipNet full native runtime is missing (did you checkout submodules?)" >&2
  exit 3
}

mkdir -p "$APP/src/main/java/app/slipnet/tunnel" \
         "$APP/src/main/java/com/tirexnet/app" \
         "$APP/src/main/cpp" "$APP/src/main/rust" \
         "$APP/src/main/jniLibs/arm64-v8a" "$APP/src/main/res" \
         "$APP/libs" "$APP/scripts" "$ROOT/third_party"

# Copy the real SlipNet Kotlin implementation, not a parser or placeholder.
cp -a "$UPSTREAM/app/src/main/java/app/slipnet/." "$APP/src/main/java/app/slipnet/"
cp -a "$UPSTREAM/app/src/full/java/app/slipnet/tunnel/SnowflakeBridge.kt" \
      "$APP/src/main/java/app/slipnet/tunnel/SnowflakeBridge.kt"

# Include full edition native binaries: NaiveProxy, Tor and obfs4proxy.
cp -a "$UPSTREAM/app/src/full/jniLibs/arm64-v8a/." "$APP/src/main/jniLibs/arm64-v8a/"

# Native tunnel support: hev-socks5-tunnel, JNI socket operations and Rust Slipstream.
cp -a "$UPSTREAM/app/src/main/cpp/." "$APP/src/main/cpp/"
cp -a "$UPSTREAM/app/src/main/rust/." "$APP/src/main/rust/"
if [[ -d "$UPSTREAM/app/src/main/assets" ]]; then
  mkdir -p "$APP/src/main/assets"
  cp -a "$UPSTREAM/app/src/main/assets/." "$APP/src/main/assets/"
fi
cp -a "$UPSTREAM/app/scripts/." "$APP/scripts/"

# Go-mobile packages (NoizDNS, VayDNS, Soar and Snowflake) are built by CI below.
# The Makefile emits golibs-full.aar in the upstream checkout; CI copies it to app/libs.

# Merge Android resources without replacing TIREXNET launcher resources or app_name.
python3 - "$UPSTREAM/app/src/main/res" "$APP/src/main/res" <<'PYRES'
import pathlib, shutil, sys, xml.etree.ElementTree as ET
src = pathlib.Path(sys.argv[1])
dst = pathlib.Path(sys.argv[2])
for folder in ("drawable", "layout", "raw", "xml"):
    sdir, ddir = src / folder, dst / folder
    if not sdir.exists():
        continue
    ddir.mkdir(parents=True, exist_ok=True)
    for item in sdir.iterdir():
        target = ddir / item.name
        if item.is_dir():
            shutil.copytree(item, target, dirs_exist_ok=True)
        elif not target.exists():
            shutil.copy2(item, target)

# Themes are needed by the internal SlipNet screen. Do not overwrite any TIREXNET values.
values_src, values_dst = src / "values", dst / "values"
values_dst.mkdir(parents=True, exist_ok=True)
for filename in ("themes.xml",):
    item = values_src / filename
    target = values_dst / filename
    if item.exists() and not target.exists():
        shutil.copy2(item, target)

# Merge upstream strings, but keep TIREXNET's app label and existing strings intact.
up = values_src / "strings.xml"
if up.exists():
    target = values_dst / "strings.xml"
    if target.exists():
        root = ET.parse(target).getroot()
    else:
        root = ET.Element("resources")
    names = {e.attrib.get("name") for e in root}
    upstream = ET.parse(up).getroot()
    for element in upstream:
        name = element.attrib.get("name", "")
        if name == "app_name" or name in names:
            continue
        root.append(element)
        names.add(name)
    ET.indent(root, space="    ")
    ET.ElementTree(root).write(target, encoding="utf-8", xml_declaration=True)
PYRES

# R and BuildConfig are generated in TIREXNET's namespace.
# TIREXNET integrates the FULL SlipNet edition only.
find "$APP/src/main/java/app/slipnet" -type f -name '*.kt' -print0 | \
  xargs -0 -r sed -i \
    -e 's/app\.slipnet\.R/com.tirexnet.app.R/g' \
    -e 's/app\.slipnet\.BuildConfig/com.tirexnet.app.BuildConfig/g' \
    -e 's/BuildConfig\.FLAVOR == "lite"/false/g'

# Kotlin 2.4/KSP cannot reify the intersection type inferred by a seven-flow
# vararg combine returning arrayOf(Any). Split the seven values into two
# strongly-typed flows and combine those typed records instead.
python3 - "$APP/src/main/java/app/slipnet/presentation/settings/SettingsViewModel.kt" <<'PYSETTINGS'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding='utf-8')
old_block = '''            val mainFlow = combine(
                preferencesDataStore.autoConnectOnBoot,
                preferencesDataStore.darkMode,
                preferencesDataStore.debugLogging,
                preferencesDataStore.proxyListenAddress,
                preferencesDataStore.proxyListenPort,
                preferencesDataStore.disableQuic,
                preferencesDataStore.vpnMtu
            ) { values ->
                arrayOf(values[0], values[1], values[2], values[3], values[4], values[5], values[6])
            }
'''
new_block = '''            data class MainSettingsCore(
                val autoConnectOnBoot: Boolean,
                val darkMode: DarkMode,
                val debugLogging: Boolean,
                val proxyListenAddress: String
            )
            data class MainSettingsNetwork(
                val proxyListenPort: Int,
                val disableQuic: Boolean,
                val vpnMtu: Int
            )
            data class MainSettings(
                val autoConnectOnBoot: Boolean,
                val darkMode: DarkMode,
                val debugLogging: Boolean,
                val proxyListenAddress: String,
                val proxyListenPort: Int,
                val disableQuic: Boolean,
                val vpnMtu: Int
            )

            val mainCoreFlow = combine(
                preferencesDataStore.autoConnectOnBoot,
                preferencesDataStore.darkMode,
                preferencesDataStore.debugLogging,
                preferencesDataStore.proxyListenAddress
            ) { autoConnect, darkMode, debugLogging, address ->
                MainSettingsCore(autoConnect, darkMode, debugLogging, address)
            }
            val mainNetworkFlow = combine(
                preferencesDataStore.proxyListenPort,
                preferencesDataStore.disableQuic,
                preferencesDataStore.vpnMtu
            ) { port, disableQuic, mtu ->
                MainSettingsNetwork(port, disableQuic, mtu)
            }
            val mainFlow = combine(mainCoreFlow, mainNetworkFlow) { core, network ->
                MainSettings(
                    core.autoConnectOnBoot,
                    core.darkMode,
                    core.debugLogging,
                    core.proxyListenAddress,
                    network.proxyListenPort,
                    network.disableQuic,
                    network.vpnMtu
                )
            }
'''
count = source.count(old_block)
if count != 1:
    raise SystemExit(f'Expected one original seven-flow mainFlow block in {path}; found {count}.')
source = source.replace(old_block, new_block, 1)
replacements = {
    'main[0] as Boolean': 'main.autoConnectOnBoot',
    'main[1] as DarkMode': 'main.darkMode',
    'main[2] as Boolean': 'main.debugLogging',
    'main[3] as String': 'main.proxyListenAddress',
    'main[4] as Int': 'main.proxyListenPort',
    'main[5] as Boolean': 'main.disableQuic',
    'main[6] as Int': 'main.vpnMtu',
}
for before, after in replacements.items():
    count = source.count(before)
    if count != 1:
        raise SystemExit(f'Expected one SettingsUiState reference {before!r}; found {count}.')
    source = source.replace(before, after, 1)
if 'arrayOf(values[0], values[1], values[2], values[3], values[4], values[5], values[6])' in source:
    raise SystemExit('Unsafe seven-flow array combine remains after patch.')
path.write_text(source, encoding='utf-8')
print('Patched SettingsViewModel: typed main settings flows (Kotlin 2.4/KSP safe).')
PYSETTINGS

# Verify that no unsupported Lite flavor checks or unsafe SettingsViewModel
# vararg-array inference remain.
if grep -R -n 'BuildConfig\.FLAVOR' "$APP/src/main/java/app/slipnet"; then
  echo "::error::Unsupported SlipNet FLAVOR reference remains."
  exit 1
fi
SETTINGS_VIEW_MODEL="$APP/src/main/java/app/slipnet/presentation/settings/SettingsViewModel.kt"
test -s "$SETTINGS_VIEW_MODEL" || {
  echo "::error::SlipNet SettingsViewModel was not staged."
  exit 1
}
grep -Fq 'data class MainSettingsCore' "$SETTINGS_VIEW_MODEL" || {
  echo "::error::Kotlin 2.4 typed SettingsViewModel patch is missing."
  exit 1
}
grep -Fq 'main.autoConnectOnBoot' "$SETTINGS_VIEW_MODEL" || {
  echo "::error::Typed SettingsViewModel fields were not applied."
  exit 1
}
if grep -Eq 'main\[[0-6]\] as ' "$SETTINGS_VIEW_MODEL"; then
  echo "::error::Unsafe SettingsViewModel array casts remain."
  exit 1
fi
# Upstream keeps constants in SlipNetApp; TIREXNET owns the one Hilt Application.
cat > "$APP/src/main/java/app/slipnet/SlipNetApp.kt" <<'KOTLIN'
package app.slipnet

/** SlipNet notification-channel identifiers shared by its services. */
object SlipNetApp {
    const val CHANNEL_VPN_STATUS = "vpn_status"
    const val CHANNEL_CONNECTION_EVENTS = "connection_events"
    const val CHANNEL_SCAN_STATUS = "scan_status"
}
KOTLIN

# One Hilt application for the combined app; do not declare a second @HiltAndroidApp.
cat > "$APP/src/main/java/com/tirexnet/app/TirexApplication.kt" <<'KOTLIN'
package com.tirexnet.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.slipnet.data.local.datastore.PreferencesDataStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TirexApplication : Application() {
    @Inject lateinit var preferencesDataStore: PreferencesDataStore
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createSlipNetNotificationChannels()
        appScope.launch { preferencesDataStore.ensureProxyPortInitialized() }
    }

    private fun createSlipNetNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            app.slipnet.SlipNetApp.CHANNEL_VPN_STATUS, "VPN Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows current VPN connection status"; setShowBadge(false) })
        manager.createNotificationChannel(NotificationChannel(
            app.slipnet.SlipNetApp.CHANNEL_CONNECTION_EVENTS, "Connection Events",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Notifications for VPN connection events" })
        manager.createNotificationChannel(NotificationChannel(
            app.slipnet.SlipNetApp.CHANNEL_SCAN_STATUS, "DNS Scan",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows DNS resolver scan progress"; setShowBadge(false) })
    }
}
KOTLIN

# Keep encrypted-bundle support in the unused upstream UI implementation.
python3 - "$APP/src/main/java/app/slipnet/presentation/MainActivity.kt" <<'PYSN'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")

old = 'uri.startsWith("slipnet://") || uri.startsWith("slipnet-enc://") || uri.startsWith("vless://")'
new = 'uri.startsWith("slipnet://") || uri.startsWith("slipnet-enc://") || uri.startsWith("slipnet-bundle-enc://") || uri.startsWith("vless://")'

if new not in source:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one SlipNet URI anchor; found {count}.")
    source = source.replace(old, new, 1)

if "slipnet-bundle-enc://" not in source:
    raise SystemExit("Encrypted SlipNet bundle scheme is missing.")

path.write_text(source, encoding="utf-8")
PYSN


# Notifications return to TIREXNET's main screen.
python3 - "$APP/src/main/java/app/slipnet/service/NotificationHelper.kt" <<'PYNOTIFY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")

old = "import app.slipnet.presentation.MainActivity"
new = "import com.tirexnet.app.MainActivity"

if new not in source:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one notification Activity import; found {count}.")
    source = source.replace(old, new, 1)

path.write_text(source, encoding="utf-8")
PYNOTIFY


# Widget VPN permission requests return to TIREXNET.
python3 - "$APP/src/main/java/app/slipnet/widget/VpnWidgetToggleReceiver.kt" <<'PYWIDGET'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")

old = "import app.slipnet.presentation.MainActivity"
new = "import com.tirexnet.app.MainActivity"

if new not in source:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one widget Activity import; found {count}.")
    source = source.replace(old, new, 1)

path.write_text(source, encoding="utf-8")
PYWIDGET


# Hilt bridge: access native SlipNet services without launching its UI.
cat > "$APP/src/main/java/com/tirexnet/app/TirexSlipNetEntryPoint.kt" <<'KOTLIN'
package com.tirexnet.app

import app.slipnet.data.export.ConfigImporter
import app.slipnet.data.local.datastore.PreferencesDataStore
import app.slipnet.domain.repository.ProfileRepository
import app.slipnet.service.VpnConnectionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TirexSlipNetEntryPoint {
    fun slipNetConnectionManager(): VpnConnectionManager
    fun slipNetConfigImporter(): ConfigImporter
    fun slipNetProfileRepository(): ProfileRepository
    fun slipNetPreferences(): PreferencesDataStore
}
KOTLIN

test -s "$APP/src/main/java/com/tirexnet/app/TirexSlipNetRuntime.kt"

echo "SlipNet runtime integrated behind the TIREXNET UI."
