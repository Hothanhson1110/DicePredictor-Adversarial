package com.example.dicepredictor

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "=== Crash $ts ===\n$sw\n\n"
                val f = File(getExternalFilesDir(null), "crash.txt")
                f.appendText(text)
            } catch (_: Throwable) { }
            default?.uncaughtException(thread, throwable)
        }
    }
}
