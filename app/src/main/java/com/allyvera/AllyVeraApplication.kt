package com.allyvera

import android.app.Application
import com.allyvera.processing.ProcessingCoordinator

class AllyVeraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ProcessingCoordinator.start(this)
    }
}
