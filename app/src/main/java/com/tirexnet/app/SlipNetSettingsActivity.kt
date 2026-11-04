package com.tirexnet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import app.slipnet.data.local.datastore.DarkMode
import app.slipnet.data.local.datastore.PreferencesDataStore
import app.slipnet.presentation.navigation.NavGraph
import app.slipnet.presentation.navigation.NavRoutes
import app.slipnet.presentation.theme.SlipstreamTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Opens the complete official SlipNet settings/navigation graph inside TIREXNET. */
@AndroidEntryPoint
class SlipNetSettingsActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesDataStore: PreferencesDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val darkMode by preferencesDataStore.darkMode.collectAsState(initial = DarkMode.SYSTEM)
            SlipstreamTheme(darkMode = darkMode) {
                val navController = rememberNavController()
                NavGraph(navController = navController)
                LaunchedEffect(navController) {
                    navController.navigate(NavRoutes.Settings.route) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}
