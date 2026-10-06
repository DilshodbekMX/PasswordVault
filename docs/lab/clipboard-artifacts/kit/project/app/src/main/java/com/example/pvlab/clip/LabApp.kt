package com.example.pvlab.clip

import android.app.Application
import android.os.Build

class LabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lab.pkg = packageName
        Lab.appContext = this
        Lab.log(
            "process_start",
            "proc" to (if (Build.VERSION.SDK_INT >= 28) getProcessName() else packageName),
            "state" to Lab.importance(),
        )
    }
}
