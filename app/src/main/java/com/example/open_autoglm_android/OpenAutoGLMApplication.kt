package com.example.open_autoglm_android

import android.app.Application
import com.ai.assistance.showerclient.ShowerEnvironment
import com.example.open_autoglm_android.virtualdisplay.ShizukuShellRunner

class OpenAutoGLMApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShowerEnvironment.shellRunner = ShizukuShellRunner()
    }
}

