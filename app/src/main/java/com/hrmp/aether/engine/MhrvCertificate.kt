package com.tirexnet.app.engine

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.therealaleph.mhrv.Native
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Exports MHRV's native MITM CA to the public Downloads folder. */
object MhrvCertificate {
    data class Exported(val location: String, val subject: String, val fingerprint: String)

    fun exportToDownloads(context: Context): Exported? {
        val app = context.applicationContext
        val temp = File(app.cacheDir, "tirexnet-mhrv-ca.crt")
        Native.setDataDir(app.filesDir.absolutePath)
        runCatching { temp.delete() } // Never accept a stale certificate from an earlier failed export.
        if (!Native.exportCa(temp.absolutePath) || !temp.exists() || temp.length() == 0L) return null

        val bytes = temp.readBytes()
        val cert = parseCertificate(bytes) ?: return null
        val displayName = "TIREXNET-MHRV-CA.crt"
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            runCatching {
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(displayName)
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            } catch (_: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            "Downloads/$displayName"
        } else {
            val directory = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            if (!directory.exists()) directory.mkdirs()
            val out = File(directory, displayName)
            out.writeBytes(bytes)
            out.absolutePath
        }

        val sha = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }
        return Exported(location, cert.subjectX500Principal.name, sha)
    }

    fun isInstalled(context: Context, fingerprint: String): Boolean {
        return runCatching {
            val expected = fingerprint.replace(":", "").uppercase()
            val store = KeyStore.getInstance("AndroidCAStore")
            store.load(null)
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val certificate = store.getCertificate(aliases.nextElement()) ?: continue
                val sha = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02X".format(it) }
                if (sha == expected) return true
            }
            false
        }.getOrDefault(false)
    }

    private fun parseCertificate(bytes: ByteArray): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }.getOrNull()
}
