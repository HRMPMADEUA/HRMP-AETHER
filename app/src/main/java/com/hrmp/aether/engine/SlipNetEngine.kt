package com.tirexnet.app.engine

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands official SlipNet share profiles to SlipNet's real Android VPN service.
 *
 * SlipNet is a separate VPN application, not a library that can safely run next
 * to TIREXNET's VpnService. Android permits only one active VPN service per
 * device. This bridge therefore opens the official app instead of pretending
 * that TIREXNET's Xray service can execute DNSTT, NoizDNS, VayDNS, Soar,
 * Slipstream, SSH, NaiveProxy, DoH or Tor profiles.
 */
object SlipNetEngine {
    const val PACKAGE_NAME = "app.slipnet"
    private const val MAIN_ACTIVITY = "app.slipnet.presentation.MainActivity"
    const val RELEASES_URL = "https://github.com/anonvector/SlipNet/releases/latest"

    sealed class OpenResult {
        data class Opened(val message: String) : OpenResult()
        data class Missing(val message: String) : OpenResult()
        data class Failed(val message: String) : OpenResult()
    }

    fun openProfile(context: Context, raw: String): OpenResult {
        val profile = EngineProfiles.decodeSlipNet(raw)
            ?: return OpenResult.Failed("SlipNet share profile is not valid.")
        return try {
            if (profile.bundle) {
                copyToClipboard(context, profile.originalUri)
                launchMain(context)
                OpenResult.Opened("Encrypted bundle copied. In SlipNet, open Import and paste it.")
            } else {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(profile.originalUri)).apply {
                    component = ComponentName(PACKAGE_NAME, MAIN_ACTIVITY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                OpenResult.Opened("Profile opened in SlipNet. Finish import and connect there.")
            }
        } catch (_: ActivityNotFoundException) {
            OpenResult.Missing("SlipNet is not installed. Install the official app to run this protocol profile.")
        } catch (_: SecurityException) {
            OpenResult.Missing("SlipNet could not be opened. Install or update the official app.")
        } catch (_: Throwable) {
            OpenResult.Failed("Could not hand this profile to SlipNet.")
        }
    }

    fun openApp(context: Context): OpenResult = try {
        launchMain(context)
        OpenResult.Opened("SlipNet opened. Its protocol and DNS settings are managed in that app.")
    } catch (_: ActivityNotFoundException) {
        OpenResult.Missing("SlipNet is not installed. Install the official app to manage its tunnel settings.")
    } catch (_: Throwable) {
        OpenResult.Failed("SlipNet could not be opened.")
    }

    fun copyProfile(context: Context, raw: String) {
        copyToClipboard(context, raw)
    }

    private fun launchMain(context: Context) {
        val intent = Intent().apply {
            component = ComponentName(PACKAGE_NAME, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun copyToClipboard(context: Context, raw: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SlipNet profile", raw))
    }
}
