package com.telehaptic.app

import android.app.Application

/** Single place for the long-lived parts; the UI and the service reach them through App.get(). */
class App : Application() {
    lateinit var log: AppLog
    lateinit var prefs: Prefs
    lateinit var capsule: Capsule
    lateinit var recorder: Recorder
    lateinit var uploader: Uploader
    lateinit var cues: Cues
    lateinit var session: Session

    override fun onCreate() {
        super.onCreate()
        instance = this
        log = AppLog(this)
        prefs = Prefs(this)
        capsule = Capsule(this, log, prefs)
        recorder = Recorder(this, capsule, log)
        uploader = Uploader(this, prefs, log)
        cues = Cues(this, log)
        session = Session(this)
        log.i("app", "start ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    companion object {
        private lateinit var instance: App
        fun get() = instance
    }
}
