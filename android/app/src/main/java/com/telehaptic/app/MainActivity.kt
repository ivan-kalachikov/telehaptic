package com.telehaptic.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.telehaptic.app.core.Kind
import com.telehaptic.app.ui.AppTheme
import com.telehaptic.app.ui.DiagnosticsScreen
import com.telehaptic.app.ui.DoneScreen
import com.telehaptic.app.ui.ExperimentsScreen
import com.telehaptic.app.ui.HomeScreen
import com.telehaptic.app.ui.LogsScreen
import com.telehaptic.app.ui.RunScreen
import com.telehaptic.app.ui.SettingsScreen
import com.telehaptic.app.ui.SetupScreen

sealed class Screen {
    data object Home : Screen()
    data object Experiments : Screen()
    data class Setup(val kind: Kind) : Screen()
    data object Run : Screen()
    data object Done : Screen()
    data object Diagnostics : Screen()
    data object Settings : Screen()
    data object Logs : Screen()
}

class Nav(private val stack: MutableList<Screen>) {
    val current get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.size - 1); true } else false
    /** Replaces everything above Home with [s]. */
    fun reset(s: Screen) { while (stack.size > 1) stack.removeAt(stack.size - 1); if (s != Screen.Home) stack.add(s) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root(this) } }
    }

    override fun onResume() {
        super.onResume()
        App.get().capsule.onUiResume()
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun Root(activity: MainActivity) {
    val app = App.get()
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val nav = remember { Nav(stack) }
    val session by app.session.ui.collectAsState()
    val running = session.phase != Session.Phase.IDLE && session.phase != Session.Phase.DONE

    LaunchedEffect(running) { activity.keepScreenOn(running) }
    LaunchedEffect(session.phase) {
        if (session.phase == Session.Phase.DONE && nav.current == Screen.Run) { nav.back(); nav.go(Screen.Done) }
    }
    BackHandler(enabled = stack.size > 1 && nav.current != Screen.Run) { nav.back() }
    BackHandler(enabled = nav.current == Screen.Run) { /* stopping is a long press on the button */ }

    when (val s = nav.current) {
        Screen.Home -> HomeScreen(nav)
        Screen.Experiments -> ExperimentsScreen(nav)
        is Screen.Setup -> SetupScreen(nav, s.kind)
        Screen.Run -> RunScreen(nav)
        Screen.Done -> DoneScreen(nav)
        Screen.Diagnostics -> DiagnosticsScreen(nav)
        Screen.Settings -> SettingsScreen(nav)
        Screen.Logs -> LogsScreen(nav)
    }
}
