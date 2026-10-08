package io.github.oxidance

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import io.github.oxidance.ui.AppScreen
import io.github.oxidance.ui.MainViewModel
import io.github.oxidance.ui.OxidanceTheme
import io.github.oxidance.ui.PlayerConnection

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var player: PlayerConnection
    /** Increases when the media notification asks to show the player. */
    private val openPlayerRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        player = PlayerConnection(this)
        handle(intent)
        setContent {
            OxidanceTheme { AppScreen(viewModel, player, openPlayerRequests.intValue) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(OPEN_PLAYER, false) == true) openPlayerRequests.intValue++
    }

    override fun onStart() {
        super.onStart()
        (application as App).sync.setForeground(true)
    }

    override fun onStop() {
        (application as App).sync.setForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    companion object {
        const val OPEN_PLAYER = "open-player"
    }
}
