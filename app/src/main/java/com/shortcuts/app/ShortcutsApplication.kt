package com.shortcuts.app

import android.app.Application
import com.shortcuts.app.diagnostics.Diagnostics

class ShortcutsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(this)
    }
}
