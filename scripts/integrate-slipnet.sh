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

mkdir -p "$APP/src/main/java/app/slipnet" \
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

# Verify that no unsupported Lite flavor checks remain.
if grep -R -n 'BuildConfig\.FLAVOR' "$APP/src/main/java/app/slipnet"; then
  echo "::error::Unsupported SlipNet FLAVOR reference remains."
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

# Patch the native SlipNet screen to accept password-protected bundle links and open its Settings tab.
python3 - "$APP/src/main/java/app/slipnet/presentation/MainActivity.kt" <<'PYSN'
from pathlib import Path
import re, sys
p = Path(sys.argv[1])
s = p.read_text()
if 'import androidx.compose.runtime.LaunchedEffect' not in s:
    s = s.replace('import androidx.compose.runtime.collectAsState\n',
                  'import androidx.compose.runtime.collectAsState\nimport androidx.compose.runtime.LaunchedEffect\n')
if 'import app.slipnet.presentation.navigation.NavRoutes' not in s:
    s = s.replace('import app.slipnet.presentation.navigation.NavGraph\n',
                  'import app.slipnet.presentation.navigation.NavGraph\nimport app.slipnet.presentation.navigation.NavRoutes\n')
pattern = re.compile(r'(?m)^(\s*)val navController = rememberNavController\(\)\n\1NavGraph\(navController = navController\)')
match = pattern.search(s)
if not match:
    raise SystemExit('Could not patch SlipNet navigation host; upstream source changed.')
indent = match.group(1)
new = (indent + 'val navController = rememberNavController()\n' +
       indent + 'LaunchedEffect(Unit) {\n' +
       indent + '    if (intent?.getBooleanExtra("com.tirexnet.app.OPEN_SLIPNET_SETTINGS", false) == true) {\n' +
       indent + '        navController.navigate(NavRoutes.Settings.route) { launchSingleTop = true }\n' +
       indent + '        intent.removeExtra("com.tirexnet.app.OPEN_SLIPNET_SETTINGS")\n' +
       indent + '    }\n' + indent + '}\n' +
       indent + 'NavGraph(navController = navController)')
s = s[:match.start()] + new + s[match.end():]
s = s.replace('uri.startsWith("slipnet://") || uri.startsWith("slipnet-enc://") || uri.startsWith("vless://")',
              'uri.startsWith("slipnet://") || uri.startsWith("slipnet-enc://") || uri.startsWith("slipnet-bundle-enc://") || uri.startsWith("vless://")')
p.write_text(s)
PYSN

# Patch TIREXNET so SlipNet profiles/settings launch the included native runtime.
python3 - "$APP/src/main/java/com/hrmp/aether/MainActivity.kt" <<'PYT'
from pathlib import Path
import re, sys
p = Path(sys.argv[1])
s = p.read_text()

def once(old, new, label):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"Expected exactly one {label} anchor; found {n}. No safe patch applied.")
    s = s.replace(old, new, 1)

# MainActivity is a legacy android.app.Activity. Use a Hilt application
# entry point instead of @AndroidEntryPoint field injection, preserving the host.
once('    private val prefs by lazy { getSharedPreferences("tirexnet", MODE_PRIVATE) }',
     '    private val prefs by lazy { getSharedPreferences("tirexnet", MODE_PRIVATE) }\n\n'
     '    private val slipNetConnectionManager: app.slipnet.service.VpnConnectionManager by lazy {\n'
     '        dagger.hilt.android.EntryPointAccessors.fromApplication(\n'
     '            applicationContext, com.tirexnet.app.TirexSlipNetEntryPoint::class.java\n'
     '        ).slipNetConnectionManager()\n'
     '    }',
     'SlipNet manager application entry point')

app_root = p.parents[6]
entry_point = app_root / 'src/main/java/com/tirexnet/app/TirexSlipNetEntryPoint.kt'
entry_point.parent.mkdir(parents=True, exist_ok=True)
entry_point.write_text("""package com.tirexnet.app

import app.slipnet.service.VpnConnectionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Accesses SlipNet's singleton service manager from TIREXNET's legacy Activity. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TirexSlipNetEntryPoint {
    fun slipNetConnectionManager(): VpnConnectionManager
}
""", encoding='utf-8')

once('settingRow(g1, Icon.GLOBE, "SlipNet protocols & settings", "DNS tunnels, resolver pools, SSH chains, Slipstream, NaiveProxy and Tor", "Open") { showSlipNetDialog() }',
     'settingRow(g1, Icon.GLOBE, "SlipNet • Advanced Settings", "Soar, DNS pools, SSH, Slipstream, NaiveProxy, DoH and Tor", "Open") { openSlipNetSettings() }\n        settingRow(g1, Icon.GLOBE, "SlipNet • Profiles, chains & scanner", "Manage profiles, chains, DNS resolver tests and diagnostics", "Manage") { openSlipNetWorkspace() }\n        settingRow(g1, Icon.GLOBE, "SlipNet license & source", "AGPL-3.0 • source code and no-warranty notice", "View") { startActivity(Intent(this, SlipNetLicenseActivity::class.java)) }',
     'SlipNet settings shortcut and license notice')
once('''        if (engine == ConfigEngine.SLIPNET) {
            showSlipNetRuntimeNotice()
            return
        }''',
     '''        if (engine == ConfigEngine.SLIPNET) {
            openSlipNetRuntime(raw)
            return
        }''', 'SlipNet connect routing')
once('            ConfigEngine.SLIPNET -> showSlipNetRuntimeNotice()',
     '            ConfigEngine.SLIPNET -> openSlipNetRuntime(pendingRaw)',
     'SlipNet engine dispatch')
once('''                desc = if (ConfigDetector.detect(currentConfig()) == ConfigEngine.SLIPNET)
                    "SlipNet profile imported. Configure it inside TIREXNET; its native tunnel runtime is not packaged in this build yet."
                else "Choose ALL or a country, then slide to connect."''',
     '''                desc = if (ConfigDetector.detect(currentConfig()) == ConfigEngine.SLIPNET)
                    "SlipNet profile detected. Open its full native tunnel screen to configure and connect."
                else "Choose ALL or a country, then slide to connect."''',
     'SlipNet home status text')
once('        ConfigEngine.SLIPNET -> "SLIPNET • RUNTIME REQUIRED"',
     '        ConfigEngine.SLIPNET -> "SLIPNET"', 'SlipNet engine label')

# Include SlipNet in region selection just like other engines.
s = s.replace('it.engine !in setOf(ConfigEngine.SLIPNET, ConfigEngine.DNS_CHANGER)',
              'it.engine != ConfigEngine.DNS_CHANGER')

# Include the real SlipNet VPN manager in the connection truth source.
once('''        val real = XrayVpnService.isActive || WgTunnelEngine.isActive || DnsChangerVpnService.isActive''',
     '''        val slipNetConnected = runCatching {
            slipNetConnectionManager.connectionState.value is app.slipnet.domain.model.ConnectionState.Connected
        }.getOrDefault(false)
        val real = XrayVpnService.isActive || WgTunnelEngine.isActive || DnsChangerVpnService.isActive || slipNetConnected''',
     'SlipNet connection reconciliation')

# Stop the SlipNet foreground VPN service when the user disconnects from TIREXNET.
once('''        var issued = false
        if (engine == "WIREGUARD"''',
     '''        var issued = false
        if (engine == "SLIPNET" || runCatching {
                val st = slipNetConnectionManager.connectionState.value
                st is app.slipnet.domain.model.ConnectionState.Connected ||
                    st is app.slipnet.domain.model.ConnectionState.Connecting
            }.getOrDefault(false)) {
            issued = true
            slipNetConnectionManager.disconnect()
        }
        if (engine == "WIREGUARD"''', 'SlipNet disconnect integration')

# Replace the old "runtime not included" dialog with in-app activity launchers.
start = s.find('    private fun showSlipNetRuntimeNotice() {')
end = s.find('    private fun startAuxiliaryProxyEngine(', start)
if start < 0 or end < 0:
    raise SystemExit('Could not locate SlipNet runtime notice boundaries.')
replacement = '''    private fun showSlipNetRuntimeNotice() {
        openSlipNetRuntime(currentConfig())
    }

    private fun openSlipNetRuntime(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) {
            toast("Select a SlipNet profile first.")
            showTab(1)
            return
        }
        startActivity(Intent(this, SlipNetConnectActivity::class.java)
            .putExtra(SlipNetConnectActivity.EXTRA_CONFIG, value)
            .putExtra(SlipNetConnectActivity.EXTRA_PROFILE_NAME, selectedItem()?.name.orEmpty()))
    }

    private fun openSlipNetSettings() {
        startActivity(Intent(this, SlipNetConnectActivity::class.java)
            .putExtra(SlipNetConnectActivity.EXTRA_OPEN_SETTINGS, true))
    }

    private fun openSlipNetWorkspace() {
        startActivity(Intent(this, SlipNetConnectActivity::class.java))
    }

    private fun showSlipNetLicenseNotice() {
        startActivity(Intent(this, SlipNetLicenseActivity::class.java))
    }

'''
s = s[:start] + replacement + s[end:]
p.write_text(s)
PYT

echo "SlipNet source integration staged successfully."
