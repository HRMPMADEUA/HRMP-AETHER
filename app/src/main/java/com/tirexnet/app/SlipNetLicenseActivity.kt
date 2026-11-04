package com.tirexnet.app

import android.graphics.Color
import android.os.Bundle
import android.text.util.Linkify
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Makes the exact upstream license and source attribution available in-app. */
class SlipNetLicenseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 12, 24))
            setPadding(dp(20), dp(24), dp(20), dp(30))
        }

        fun heading(value: String) {
            root.addView(TextView(this).apply {
                text = value
                textSize = 17f
                setTextColor(Color.rgb(100, 225, 255))
                setPadding(0, dp(18), 0, dp(8))
            })
        }

        fun body(value: String) {
            root.addView(TextView(this).apply {
                text = value
                textSize = 12.5f
                setTextColor(Color.rgb(220, 230, 242))
                setTextIsSelectable(true)
                setLineSpacing(dp(3).toFloat(), 1f)
                autoLinkMask = Linkify.WEB_URLS
                Linkify.addLinks(this, Linkify.WEB_URLS)
                setPadding(0, 0, 0, dp(10))
            })
        }

        val notice = readAsset("third_party/SLIPNET-SOURCE-NOTICE.md")
        val license = readAsset("third_party/SLIPNET-AGPL-3.0.txt")

        heading("SlipNet — source & third-party notice")
        body(notice)
        heading("GNU Affero General Public License v3")
        body(license)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    private fun readAsset(path: String): String = runCatching {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse {
        "The license notice is not present in this build. Please obtain the matching source package from the TIREXNET repository."
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
