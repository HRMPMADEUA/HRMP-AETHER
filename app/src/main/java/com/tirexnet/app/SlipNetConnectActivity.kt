package com.tirexnet.app

import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.slipnet.data.export.ConfigImporter
import app.slipnet.data.export.ImportResult
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.repository.ProfileRepository
import app.slipnet.service.VpnConnectionManager
import app.slipnet.util.LockPasswordUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Imports a selected official SlipNet share profile into SlipNet's own Room
 * database, requests Android's VPN permission, and starts the embedded runtime.
 * The official SlipNet workspace then becomes the visible connection screen.
 */
@AndroidEntryPoint
class SlipNetConnectActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CONFIG = "com.tirexnet.app.extra.SLIPNET_CONFIG"
        const val EXTRA_PROFILE_NAME = "com.tirexnet.app.extra.SLIPNET_PROFILE_NAME"
        const val EXTRA_USE_PREFERENCE_CONFIG = "com.tirexnet.app.extra.SLIPNET_USE_PREFERENCE_CONFIG"
        const val EXTRA_OPEN_SETTINGS = "com.tirexnet.app.extra.SLIPNET_OPEN_SETTINGS"
    }

    @Inject lateinit var configImporter: ConfigImporter
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var connectionManager: VpnConnectionManager

    private var pendingProfile: ServerProfile? = null
    private var waitingForPermission = false
    private var importedConfig = ""
    private var connectRequested = false
    private var openSettingsAfterImport = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectAndOpenWorkspace()
        } else {
            showFailure("Android VPN permission was denied. SlipNet cannot create its VPN tunnel without this permission.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showProgress("Preparing SlipNet profile…")

        importedConfig = intent.getStringExtra(EXTRA_CONFIG).orEmpty().trim()
        if (importedConfig.isBlank() && intent.getBooleanExtra(EXTRA_USE_PREFERENCE_CONFIG, false)) {
            val handoffPrefs = getSharedPreferences("tirexnet", MODE_PRIVATE)
            importedConfig = handoffPrefs.getString("slipnet_handoff_config", "").orEmpty().trim()
            handoffPrefs.edit().remove("slipnet_handoff_config").apply()
        }
        connectRequested = importedConfig.isNotBlank()
        openSettingsAfterImport = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)

        if (importedConfig.isBlank()) {
            val savedConfigs = collectSavedSlipNetConfigs()
            if (savedConfigs.isEmpty()) {
                openDestination()
                return
            }
            importedConfig = savedConfigs.joinToString("\n")
        } else if (!importedConfig.startsWith("slipnet-bundle-enc://", true)) {
            val extras = collectSavedSlipNetConfigs().filterNot { it == importedConfig }
            if (extras.isNotEmpty()) importedConfig = (listOf(importedConfig) + extras).joinToString("\n")
        }
        importAndPrepare(importedConfig)
    }

    private fun showProgress(message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(ProgressBar(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = message
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    /** Collect the SlipNet/VLESS share profiles already held by TIREXNET, including offline subscription cache. */
    private fun collectSavedSlipNetConfigs(): List<String> {
        val preferences = getSharedPreferences("tirexnet", MODE_PRIVATE)
        val result = LinkedHashSet<String>()
        for (key in listOf("saved_server_profiles", "remote_servers_cache")) {
            val text = preferences.getString(key, "[]").orEmpty()
            val array = runCatching { JSONArray(text) }.getOrElse { JSONArray() }
            for (index in 0 until array.length()) {
                val raw = array.optJSONObject(index)?.optString("raw", "")?.trim().orEmpty()
                val lower = raw.lowercase()
                if (lower.startsWith("slipnet://") || lower.startsWith("slipnet-enc://") || lower.startsWith("vless://")) {
                    result.add(raw)
                }
            }
        }
        return result.toList()
    }

    private fun importAndPrepare(raw: String, password: String? = null) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    configImporter.parseAndImport(
                        raw,
                        localDeviceId = connectionManager.getDeviceId(),
                        bundlePassword = password
                    )
                }.getOrElse { ImportResult.Error(it.message ?: "SlipNet could not decode this profile.") }
            }

            when (result) {
                is ImportResult.Success -> saveAndRequestPermission(result.profiles)
                is ImportResult.Error -> showFailure(result.message)
                ImportResult.NeedsPassword -> askBundlePassword()
            }
        }
    }

    private fun askBundlePassword() {
        val input = EditText(this).apply {
            hint = "SlipNet export password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Unlock SlipNet bundle")
            .setMessage("Enter the password used when this SlipNet bundle was exported.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Unlock", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = input.text.toString()
                        if (password.isBlank()) {
                            input.error = "Password is required"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        showProgress("Unlocking SlipNet bundle…")
                        importAndPrepare(importedConfig, password)
                    }
                }
                dialog.show()
            }
    }

    private suspend fun saveAndRequestPermission(profiles: List<ServerProfile>) {
        if (profiles.isEmpty()) {
            showFailure("The SlipNet share did not contain a usable profile.")
            return
        }

        val prepared = try {
            withContext(Dispatchers.IO) {
                val existing = profileRepository.getAllProfiles().first()
                val saved = ArrayList<ServerProfile>()
                for (profile in profiles) {
                    val match = existing.firstOrNull { savedProfile ->
                        profile.copy(
                            id = savedProfile.id,
                            isActive = savedProfile.isActive,
                            createdAt = savedProfile.createdAt,
                            updatedAt = savedProfile.updatedAt,
                            lastConnectedAt = savedProfile.lastConnectedAt,
                            sortOrder = savedProfile.sortOrder,
                            isPinned = savedProfile.isPinned
                        ) == savedProfile
                    }
                    val id = if (match == null) {
                        profileRepository.saveProfile(profile.copy(isActive = false))
                    } else {
                        profileRepository.updateProfile(profile.copy(
                            id = match.id,
                            isActive = match.isActive,
                            createdAt = match.createdAt,
                            updatedAt = match.updatedAt,
                            lastConnectedAt = match.lastConnectedAt,
                            sortOrder = match.sortOrder,
                            isPinned = match.isPinned
                        ))
                        match.id
                    }
                    profileRepository.getProfileById(id)?.let(saved::add)
                }
                saved.firstOrNull()
            }
        } catch (error: Throwable) {
            showFailure("SlipNet could not save the imported profile: ${error.message ?: "database error"}")
            return
        }

        if (prepared == null) {
            showFailure("SlipNet could not save these profiles to its local profile database.")
            return
        }

        if (!connectRequested) {
            openDestination()
            return
        }

        if (prepared.isLocked) {
            askProfileLockPassword(prepared)
            return
        }
        requestVpnPermission(prepared)
    }

    /** Preserve SlipNet's profile-lock protection instead of auto-connecting a locked share. */
    private fun askProfileLockPassword(profile: ServerProfile) {
        val input = EditText(this).apply {
            hint = "Profile password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Unlock SlipNet profile")
            .setMessage("This profile is password-locked. Enter its profile password to continue.")
            .setView(input)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Unlock", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = input.text.toString()
                        val valid = runCatching {
                            profile.lockPasswordHash.isNotBlank() &&
                                LockPasswordUtil.verifyPassword(password, profile.lockPasswordHash)
                        }.getOrDefault(false)
                        if (!valid) {
                            input.error = "Incorrect profile password"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        requestVpnPermission(profile)
                    }
                }
                dialog.show()
            }
    }

    private fun requestVpnPermission(profile: ServerProfile) {
        pendingProfile = profile
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            waitingForPermission = true
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            connectAndOpenWorkspace()
        }
    }

    private fun connectAndOpenWorkspace() {
        val profile = pendingProfile ?: run {
            showFailure("The selected SlipNet profile is no longer available.")
            return
        }
        waitingForPermission = false

        getSharedPreferences("tirexnet", MODE_PRIVATE).edit()
            .putString("active_engine", "SLIPNET")
            .putString("connection_message", "Starting SlipNet…")
            .putBoolean("connection_active", false)
            .apply()

        try {
            connectionManager.connect(profile)
            openWorkspace()
        } catch (error: Throwable) {
            showFailure("SlipNet could not start the VPN service: ${error.message ?: "service start failed"}")
        }
    }

    private fun openWorkspace() {
        startActivity(Intent(this, app.slipnet.presentation.MainActivity::class.java))
        finish()
    }

    private fun openDestination() {
        val target = if (openSettingsAfterImport) {
            SlipNetSettingsActivity::class.java
        } else {
            app.slipnet.presentation.MainActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun showFailure(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, "SlipNet: $message", Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this)
            .setTitle("SlipNet could not start")
            .setMessage(message)
            .setPositiveButton("Open SlipNet") { _, _ -> openDestination() }
            .setNegativeButton("Close") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        if (waitingForPermission) waitingForPermission = false
    }

}
