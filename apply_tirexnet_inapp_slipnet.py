#!/usr/bin/env python3
"""Apply the TIREXNET in-app SlipNet integration patch."""
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path.cwd()

FILES = {
    "main": ROOT / "app/src/main/java/com/hrmp/aether/MainActivity.kt",
    "integrator": ROOT / "scripts/integrate-slipnet.sh",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "workflow": ROOT / ".github/workflows/build-apk.yml",
}

RUNTIME_SOURCE = ROOT / "app/src/main/java/com/tirexnet/app/TirexSlipNetRuntime.kt"
RUNTIME_TARGET = ROOT / "app/src/main/java/com/tirexnet/app/TirexSlipNetRuntime.kt"

for label, path in FILES.items():
    if not path.is_file():
        raise SystemExit(
            f"Missing {label} file: {path.relative_to(ROOT)}. "
            "Run this from the repository root."
        )

if not RUNTIME_SOURCE.is_file():
    raise SystemExit("TirexSlipNetRuntime.kt is missing from the app source directory.")

contents = {
    key: path.read_text(encoding="utf-8")
    for key, path in FILES.items()
}

main = contents["main"]
integrator = contents["integrator"]
manifest = contents["manifest"]
workflow = contents["workflow"]


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one anchor, found {count}. "
            "No source files have been written."
        )
    return text.replace(old, new, 1)


# ============================================================
# MAIN ACTIVITY — runtime ownership and state observation
# ============================================================

main = once(
    main,
    "import java.util.concurrent.Executors\n",
    """import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
""",
    "coroutine imports",
)

main = once(
    main,
    '    private val prefs by lazy { getSharedPreferences("tirexnet", MODE_PRIVATE) }\n',
    '''    private val prefs by lazy { getSharedPreferences("tirexnet", MODE_PRIVATE) }
    private val slipNetRuntime by lazy { TirexSlipNetRuntime(applicationContext) }
    private val slipNetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var slipNetObserver: Job? = null
''',
    "runtime fields",
)

main = once(
    main,
    """        reconcileState()
        render()
        applySystemBars() // after setContentView: the decor view exists now
""",
    """        observeSlipNetConnection()
        reconcileState()
        render()
        applySystemBars() // after setContentView: the decor view exists now
""",
    "runtime observer registration",
)

main = once(
    main,
    "        packageExecutor.shutdownNow()\n        super.onDestroy()\n",
    "        packageExecutor.shutdownNow()\n"
    "        slipNetObserver?.cancel()\n"
    "        slipNetScope.cancel()\n"
    "        super.onDestroy()\n",
    "runtime coroutine cleanup",
)

observer = '''    private fun observeSlipNetConnection() {
        slipNetObserver?.cancel()
        slipNetObserver = slipNetScope.launch {
            slipNetRuntime.connectionState.collect { connection ->
                if (prefs.getString("active_engine", "") != "SLIPNET") {
                    return@collect
                }

                when (connection) {
                    is app.slipnet.domain.model.ConnectionState.Connected -> {
                        applyStatus("Connected.", true)
                    }

                    is app.slipnet.domain.model.ConnectionState.Connecting -> {
                        applyStatus("Starting SlipNet…", false)
                    }

                    is app.slipnet.domain.model.ConnectionState.Disconnecting -> {
                        applyStatus("Disconnecting…", false)
                    }

                    is app.slipnet.domain.model.ConnectionState.Error -> {
                        applyStatus("SlipNet error: ${connection.message}", false)
                        prefs.edit().putString("active_engine", "").apply()
                    }

                    is app.slipnet.domain.model.ConnectionState.Disconnected -> {
                        applyStatus("Disconnected.", false)
                        prefs.edit().putString("active_engine", "").apply()
                    }
                }
            }
        }
    }

'''

main = once(
    main,
    "    // =====================================================================\n"
    "    // Theme\n",
    observer
    + "    // =====================================================================\n"
    + "    // Theme\n",
    "runtime state observer",
)

main = once(
    main,
    "        val real = XrayVpnService.isActive || WgTunnelEngine.isActive || DnsChangerVpnService.isActive\n",
    "        val slipNetActive = runCatching { slipNetRuntime.isConnected() }.getOrDefault(false)\n"
    "        val real = XrayVpnService.isActive || WgTunnelEngine.isActive || "
    "DnsChangerVpnService.isActive || slipNetActive\n",
    "connection state reconciliation",
)

main = once(
    main,
    '''                desc = if (ConfigDetector.detect(currentConfig()) == ConfigEngine.SLIPNET)
                    "SlipNet profile imported. Configure it inside TIREXNET; its native tunnel runtime is not packaged in this build yet."
                else "Choose ALL or a country, then slide to connect."
''',
    '''                desc = if (ConfigDetector.detect(currentConfig()) == ConfigEngine.SLIPNET)
                    "SlipNet is ready. Connect directly from TIREXNET."
                else "Choose ALL or a country, then slide to connect."
''',
    "native SlipNet home description",
)

main = once(
    main,
    '        ConfigEngine.SLIPNET -> "SLIPNET • RUNTIME REQUIRED"\n',
    '        ConfigEngine.SLIPNET -> "SLIPNET"\n',
    "SlipNet engine label",
)

main = once(
    main,
    '''            isPermission -> OrbState.PERMISSION
            lower.contains("disconnected") -> OrbState.DISCONNECTED
            else -> OrbState.CONNECTING
''',
    '''            isPermission -> OrbState.PERMISSION
            lower.contains("disconnecting") -> OrbState.DISCONNECTING
            lower.contains("disconnected") -> OrbState.DISCONNECTED
            else -> OrbState.CONNECTING
''',
    "disconnecting status mapping",
)

main = once(
    main,
    '''        if (engine == ConfigEngine.SLIPNET) {
            showSlipNetRuntimeNotice()
            return
        }
''',
    "",
    "old SlipNet connect early return",
)

main = once(
    main,
    '''        if (proxyMode && engine !in setOf(ConfigEngine.XRAY, ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.SNI_SPOOF)) {
            toast("Local proxy mode supports Xray, MHRV, StormDNS and Patterniha profiles only.")
            return
        }
''',
    '''        if (
            proxyMode &&
            engine !in setOf(ConfigEngine.XRAY, ConfigEngine.MHRV, ConfigEngine.DNS_TUNNEL, ConfigEngine.SNI_SPOOF) &&
            !(engine == ConfigEngine.SLIPNET && prefs.getBoolean("slipnet_proxy_only", false))
        ) {
            toast("Local proxy mode supports Xray, MHRV, StormDNS, Patterniha SNI, or SlipNet proxy-only profiles.")
            return
        }
''',
    "SlipNet proxy mode routing",
)

main = once(
    main,
    "        val permission = if (proxyMode) null else VpnService.prepare(this)\n",
    "        val permission = if (proxyMode && engine != ConfigEngine.SLIPNET) "
    "null else VpnService.prepare(this)\n",
    "SlipNet VPN permission",
)

main = once(
    main,
    "            ConfigEngine.SLIPNET -> showSlipNetRuntimeNotice()\n",
    "            ConfigEngine.SLIPNET -> startSlipNetRuntime(pendingRaw)\n",
    "SlipNet engine dispatcher",
)


# ============================================================
# SLIPNET CONNECT / PASSWORD HANDLERS
# ============================================================

runtime_methods = '''    private fun startSlipNetRuntime(raw: String, bundlePassword: String? = null) {
        val config = raw.trim()

        if (config.isBlank()) {
            toast("Select a SlipNet profile first.")
            return
        }

        pendingRaw = config
        pendingEngine = ConfigEngine.SLIPNET

        prefs.edit()
            .putString("active_engine", "SLIPNET")
            .putString(TirexNotifier.KEY_PROFILE_NAME, selectedItem()?.name.orEmpty())
            .apply()

        applyStatus("Preparing SlipNet profile…", false)

        slipNetScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    slipNetRuntime.importAndSave(config, bundlePassword)
                }
            } catch (error: Throwable) {
                TirexSlipNetImportResult.Failure(
                    error.message ?: "SlipNet profile could not be imported."
                )
            }

            when (result) {
                is TirexSlipNetImportResult.NeedsPassword -> {
                    requestSlipNetBundlePassword(config)
                }

                is TirexSlipNetImportResult.Failure -> {
                    prefs.edit().putString("active_engine", "").apply()
                    applyStatus("SlipNet error: ${result.message}", false)
                    toast(result.message)
                }

                is TirexSlipNetImportResult.Ready -> {
                    prefs.edit()
                        .putString(TirexNotifier.KEY_PROFILE_NAME, result.profileName)
                        .apply()

                    if (slipNetRuntime.preparedProfileNeedsPassword()) {
                        requestSlipNetProfilePassword()
                    } else {
                        connectPreparedSlipNet()
                    }
                }
            }
        }
    }

    private fun requestSlipNetBundlePassword(raw: String) {
        val input = EditText(this).apply {
            hint = "Bundle password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            textSize = 13f
            setPadding(dpi(12), dpi(9), dpi(12), dpi(9))
            background = fieldBackground(false)
        }

        val dialog = dialogBuilder()
            .setTitle("Unlock SlipNet bundle")
            .setMessage("Enter the password used when this SlipNet bundle was exported.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> cancelSlipNetStart() }
            .setPositiveButton("Continue", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text.toString()

                if (password.isBlank()) {
                    input.error = "Password required"
                    return@setOnClickListener
                }

                dialog.dismiss()
                startSlipNetRuntime(raw, password)
            }
        }

        dialog.show()
    }

    private fun requestSlipNetProfilePassword() {
        val input = EditText(this).apply {
            hint = "Profile password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            textSize = 13f
            setPadding(dpi(12), dpi(9), dpi(12), dpi(9))
            background = fieldBackground(false)
        }

        val dialog = dialogBuilder()
            .setTitle("Unlock SlipNet profile")
            .setMessage("This profile is protected by its owner. Enter its profile password to connect.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> cancelSlipNetStart() }
            .setPositiveButton("Unlock", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text.toString()

                if (!slipNetRuntime.verifyPreparedProfilePassword(password)) {
                    input.error = "Incorrect profile password"
                    return@setOnClickListener
                }

                dialog.dismiss()
                connectPreparedSlipNet()
            }
        }

        dialog.show()
    }

    private fun connectPreparedSlipNet() {
        slipNetScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    slipNetRuntime.applySettings(prefs)
                }

                prefs.edit().putString("active_engine", "SLIPNET").apply()
                applyStatus("Starting SlipNet…", false)
                slipNetRuntime.connectPrepared()

            } catch (error: Throwable) {
                prefs.edit().putString("active_engine", "").apply()
                applyStatus(
                    "SlipNet error: ${error.message ?: "connection failed"}",
                    false
                )
                toast("SlipNet could not start. Check the profile and try again.")
            }
        }
    }

    private fun cancelSlipNetStart() {
        prefs.edit().putString("active_engine", "").apply()
        applyStatus("Disconnected.", false)
    }

'''

main = once(
    main,
    "    private fun startSelectedEngine() {\n",
    runtime_methods + "    private fun startSelectedEngine() {\n",
    "SlipNet connect/password handlers",
)

main = once(
    main,
    '        var issued = false\n        if (engine == "WIREGUARD"',
    '        var issued = false\n'
    '        if (engine == "SLIPNET" || slipNetRuntime.isConnectedOrConnecting()) {\n'
    '            issued = true\n'
    '            slipNetRuntime.disconnect()\n'
    '        }\n'
    '        if (engine == "WIREGUARD"',
    "SlipNet disconnect dispatcher",
)


# ============================================================
# SLIPNET SETTINGS
# ============================================================

main, heading_count = re.subn(
    r'heading\("SlipNet",\s*"[^"]*"\)',
    'heading("SlipNet", "Settings are saved in TIREXNET and applied to SlipNet’s native tunnel service. Imported profiles retain their own protocol configuration.")',
    main,
    count=1,
)

if heading_count != 1:
    raise RuntimeError("Could not update SlipNet settings description.")

notice_pattern = re.compile(
    r'    private fun showSlipNetRuntimeNotice\(\) \{[\s\S]*?\n    \}\n\n'
    r'(?=    private fun startAuxiliaryProxyEngine\()'
)

main, notice_count = notice_pattern.subn("", main, count=1)

if notice_count != 1:
    raise RuntimeError("Could not remove the old SlipNet runtime-not-included dialog.")

settings_toast_pattern = re.compile(
    r'                dialog\.dismiss\(\)\n'
    r'                when \{\n'
    r'                    torProfileSaved -> toast\([\s\S]*?\n'
    r'                \}\n',
    re.MULTILINE,
)

settings_toast_replacement = '''                dialog.dismiss()
                slipNetScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            slipNetRuntime.applySettings(prefs)
                        }

                        when {
                            torProfileSaved -> toast("تنظیمات SlipNet اعمال شد و پروفایل Tor محلی ذخیره شد.")
                            torEditUnavailable -> toast("تنظیمات SlipNet اعمال شد؛ لینک Tor رمزگذاری‌شده مستقیماً قابل ویرایش نیست.")
                            else -> toast("SlipNet settings saved and applied to the native runtime.")
                        }

                    } catch (_: Throwable) {
                        toast("تنظیمات ذخیره شدند، اما اعمال آن‌ها روی موتور SlipNet ناموفق بود.")
                    }
                }
'''

main, toast_count = settings_toast_pattern.subn(
    settings_toast_replacement,
    main,
    count=1,
)

if toast_count != 1:
    raise RuntimeError("Could not update SlipNet settings result message.")

if (
    "showSlipNetRuntimeNotice" in main
    or "openSlipNetRuntime" in main
    or "SlipNetConnectActivity" in main
):
    raise RuntimeError("MainActivity still contains a separate SlipNet screen hand-off.")


# ============================================================
# INTEGRATION SCRIPT
# ============================================================

marker = (
    "# Patch the native SlipNet launcher to accept password-protected bundle deep links.\n"
)

script_start = integrator.find(marker)

if script_start < 0:
    raise RuntimeError("Could not locate the old SlipNet UI launcher patch.")

script_tail = '''# Keep encrypted-bundle support in the unused upstream UI implementation.
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
'''

integrator = integrator[:script_start] + script_tail


# ============================================================
# MANIFEST — NO SEPARATE SLIPNET SCREEN
# ============================================================

manifest_pattern = re.compile(
    r'        <!-- SlipNet is an internal screen of the same installed TIREXNET app\. -->\s*'
    r'<activity\s+android:name="app\.slipnet\.presentation\.MainActivity"[\s\S]*?</activity>',
    re.MULTILINE,
)

manifest_replacement = '''        <!-- Upstream SlipNet UI is private; TIREXNET owns all user-facing screens. -->
        <activity
            android:name="app.slipnet.presentation.MainActivity"
            android:exported="false"
            android:theme="@style/Theme.SlipNet"
            android:windowSoftInputMode="adjustResize" />'''

manifest, manifest_count = manifest_pattern.subn(
    manifest_replacement,
    manifest,
    count=1,
)

if manifest_count != 1:
    raise RuntimeError("Could not make the upstream SlipNet Activity private.")


# ============================================================
# WORKFLOW VALIDATION
# ============================================================

required_workflow_markers = (
    "python3 apply_tirexnet_inapp_slipnet.py",
    "startSlipNetRuntime(pendingRaw)",
    "slipNetRuntime.disconnect()",
    "slipNetRuntime.connectionState.collect",
    'activity_nodes = {x.get(android + "name"): x for x in app.findall("activity")}',
    'assert upstream_ui.get(android + "exported") == "false"',
    'assert not upstream_ui.findall("intent-filter")',
)

for marker in required_workflow_markers:
    if marker not in workflow:
        raise RuntimeError(
            f"Workflow is missing required in-app SlipNet check: {marker}"
        )


# ============================================================
# VALIDATE EVERYTHING BEFORE WRITING FILES
# ============================================================

root = ET.fromstring(manifest)

android_ns = "{http://schemas.android.com/apk/res/android}"
app_node = root.find("application")

assert app_node is not None

slip_activity = next(
    activity
    for activity in app_node.findall("activity")
    if activity.get(android_ns + "name") == "app.slipnet.presentation.MainActivity"
)

assert slip_activity.get(android_ns + "exported") == "false"
assert not slip_activity.findall("intent-filter")

for required in (
    "startSlipNetRuntime(pendingRaw)",
    "slipNetRuntime.connectionState.collect",
    "slipNetRuntime.disconnect()",
    "slipNetRuntime.applySettings(prefs)",
):
    if required not in main:
        raise RuntimeError(f"MainActivity missing required wiring: {required}")

if not RUNTIME_SOURCE.is_file():
    raise RuntimeError("TirexSlipNetRuntime.kt is missing.")

# Backups are made before writing source changes.
for key, path in FILES.items():
    if key == "workflow":
        continue

    backup = path.with_suffix(path.suffix + ".inapp-slipnet.bak")
    shutil.copy2(path, backup)

FILES["main"].write_text(main, encoding="utf-8")
FILES["integrator"].write_text(integrator, encoding="utf-8")
FILES["manifest"].write_text(manifest, encoding="utf-8")

print("TIREXNET in-app SlipNet integration applied.")
print("Changed files:")
for path in (FILES["main"], FILES["integrator"], FILES["manifest"]):
    print(" -", path.relative_to(ROOT))

print("Runtime source: app/src/main/java/com/tirexnet/app/TirexSlipNetRuntime.kt")
print("Backups: *.inapp-slipnet.bak")
print("Next: review git diff, commit the changes, and check GitHub Actions.")
