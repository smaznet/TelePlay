package com.smaznet.teleplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Surface
import com.smaznet.teleplay.ui.nav.AppContainer
import com.smaznet.teleplay.ui.player.PlayerScreen
import com.smaznet.teleplay.ui.nav.TelePlayNavHost
import com.smaznet.teleplay.ui.theme.TelePlayTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as TelePlayApp).services.onForeground()
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = (application as TelePlayApp).services
        val container: AppContainer = services
        setContent {
            TelePlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                ) {
                    run {
                        TelePlayNavHost(container = container, playerRoute = { videoId, fromStart, onBack ->
                            PlayerScreen(videoId, fromStart, services, onBack)
                        })
                    }
                }
            }
        }
    }
}
