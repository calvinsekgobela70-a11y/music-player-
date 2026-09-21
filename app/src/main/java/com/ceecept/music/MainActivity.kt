package com.ceecept.music

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ceecept.music.ui.navigation.CeeceptRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleViewIntent(intent)
        // Validate bundled fonts eagerly; fall back to system fonts if broken.
        val useCustomFont = try {
            resources.getFont(R.font.inter_regular)
            true
        } catch (e: Exception) {
            CrashReporter.recordSoft(this, "font-preload", e)
            false
        }
        setContent {
            CeeceptRoot(app = ceeceptApp(), useCustomFont = useCustomFont)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        var title = "Audio file"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) title = it.getString(0) ?: title
            }
        } catch (e: Exception) {
        }
        ceeceptApp().playerConnection.playExternal(uri, title)
    }
}
