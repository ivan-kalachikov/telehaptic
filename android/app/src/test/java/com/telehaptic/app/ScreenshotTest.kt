package com.telehaptic.app

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.telehaptic.app.core.Kind
import com.telehaptic.app.core.MagInfo
import com.telehaptic.app.ui.AppTheme
import com.telehaptic.app.ui.DiagnosticsScreen
import com.telehaptic.app.ui.DoneScreen
import com.telehaptic.app.ui.ExperimentsScreen
import com.telehaptic.app.ui.HomeScreen
import com.telehaptic.app.ui.RunScreen
import com.telehaptic.app.ui.SetupScreen
import com.telehaptic.app.ui.TrainScreen
import com.telehaptic.app.ui.TypingScreen
import com.telehaptic.app.core.Evaluation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders the main screens with example data into docs/screens/. Run: gradle testDebugUnitTest -Pscreenshots */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = App.get()
    private val connected = Capsule.Ui(
        status = Capsule.Status.CONNECTED, firmware = "0.1.4", bootReason = "power_on", batteryMv = 3980,
        mags = listOf(MagInfo(1, "QMC5883P", "ch0", 1000.0), MagInfo(2, "QMC5883P", "ch1", 1000.0)), imu = true,
        scan = listOf("SCAN begin mux=yes", "SCAN ch0 0x2C QMC5883P mag1", "SCAN ch1 0x2C QMC5883P mag2", "SCAN ch2 EMPTY HINT_PULLUPS", "SCAN end mags=2"),
        rateHz = 100, rangeG = 30, osr = "8/8", measuredHz = 99.8, recentLoss = 0.002, lost = 3, received = 12034, rttMs = 18.0,
        syncResidualMs = 0.6, driftPpm = 12.0,
        live = Capsule.Live(
            magUt = listOf(doubleArrayOf(31.2, -12.4, 40.8), doubleArrayOf(29.9, -10.1, 38.2)), sat = listOf(false, false),
            gyrDps = doubleArrayOf(0.4, -0.2, 0.1), accG = doubleArrayOf(0.02, -0.98, 0.11),
        ),
    )

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun nav(vararg s: Screen) = Nav(mutableStateListOf(*s))

    @Test fun home() {
        app.capsule.showForPreview(connected)
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { HomeScreen(nav(Screen.Home)) } }
        shot("1-home")
    }

    @Test fun homeNotFound() {
        app.capsule.showForPreview(Capsule.Ui(status = Capsule.Status.NOT_FOUND))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { HomeScreen(nav(Screen.Home)) } }
        shot("2-home-not-found")
    }

    @Test fun experiments() {
        app.capsule.showForPreview(connected)
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { ExperimentsScreen(nav(Screen.Home, Screen.Experiments)) } }
        shot("3-experiments")
    }

    @Test fun setup() {
        app.capsule.showForPreview(connected)
        app.prefs.magnet = "8x2"; app.prefs.distanceMm = 38
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { SetupScreen(nav(Screen.Home, Screen.Setup(Kind.KEYS)), Kind.KEYS) } }
        shot("4-setup-keys")
    }

    @Test fun run() {
        app.capsule.showForPreview(connected)
        app.session.showForPreview(Session.Ui(phase = Session.Phase.HOLD, kind = Kind.KEYS, key = "5", trial = 13, trials = 48))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { RunScreen(nav(Screen.Home, Screen.Run)) } }
        shot("5-run-keys")
    }

    @Test fun runCalibration() {
        app.capsule.showForPreview(connected)
        app.session.showForPreview(Session.Ui(phase = Session.Phase.ROTATE, kind = Kind.CALIBRATION,
            phaseEndsAt = android.os.SystemClock.elapsedRealtime() + 27000))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { RunScreen(nav(Screen.Home, Screen.Run)) } }
        shot("6-run-calibration")
    }

    @Test fun done() {
        app.session.showForPreview(Session.Ui(phase = Session.Phase.DONE, kind = Kind.KEYS,
            result = RecordingResult(File("KEYS_table_20261111-153012.zip"), null, false, 222.0, 22180, 41, 0, 2, 0, 0)))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { DoneScreen(nav(Screen.Home, Screen.Done)) } }
        shot("7-done")
    }

    @Test fun diagnostics() {
        app.capsule.showForPreview(connected)
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { DiagnosticsScreen(nav(Screen.Home, Screen.Diagnostics)) } }
        shot("8-diagnostics")
    }

    private val trained = Brain.Summary(trained = true, date = "11 нояб., 15:40", correct = 41, total = 48,
        confusions = listOf(Triple("2", "5", 3), Triple("6", "9", 2), Triple("#", "9", 1)), weakKeys = listOf("5", "2", "9"), touches = 48)

    @Test fun homeTrained() {
        app.capsule.showForPreview(connected)
        app.brain.showForPreview(trained)
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { HomeScreen(nav(Screen.Home)) } }
        shot("9-home-trained")
    }

    @Test fun trainHold() {
        app.capsule.showForPreview(connected)
        app.training.showForPreview(Training.Ui(phase = Training.Phase.HOLDS, key = "5", holdStep = Training.HoldStep.HOLD, index = 13, total = 24))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { TrainScreen(nav(Screen.Home, Screen.Train), retrain = false) } }
        shot("10-train-hold")
    }

    @Test fun trainResult() {
        app.capsule.showForPreview(connected)
        app.training.showForPreview(Training.Ui(phase = Training.Phase.RESULT,
            report = Evaluation.Report(41, 48, listOf(Triple("2", "5", 3), Triple("6", "9", 2), Triple("#", "9", 1)), listOf("5", "2", "9"))))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { TrainScreen(nav(Screen.Home, Screen.Train), retrain = false) } }
        shot("11-train-result")
    }

    @Test fun retrainResult() {
        app.capsule.showForPreview(connected)
        app.training.showForPreview(Training.Ui(phase = Training.Phase.RESULT, retrain = true,
            before = Evaluation.Report(15, 24, emptyList(), emptyList()), report = Evaluation.Report(21, 24, listOf(Triple("6", "9", 2)), listOf("9"))))
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { TrainScreen(nav(Screen.Home, Screen.Retrain), retrain = true) } }
        shot("12-retrain-result")
    }

    @Test fun typing() {
        app.capsule.showForPreview(connected)
        app.brain.showForPreview(trained)
        rule.mainClock.autoAdvance = false
        rule.setContent { AppTheme { TypingScreen(nav(Screen.Home, Screen.Typing)) } }
        app.typing.showForPreview(TypingController.Ui(text = "привет как дела", candidates = listOf("дела", "жела", "дел"), variant = 0,
            lastKey = "2", flashUntil = Long.MAX_VALUE, posterior = 0.82, second = "5", secondPosterior = 0.11, ready = true))
        shot("13-typing")
    }
}
