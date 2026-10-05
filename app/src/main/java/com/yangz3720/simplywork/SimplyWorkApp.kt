package com.yangz3720.simplywork

import android.app.Application

/**
 * Application entry: builds the composition root once per process.
 */
class SimplyWorkApp : Application() {

    companion object {
        lateinit var instance: SimplyWorkApp
        lateinit var container: AppContainer
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }
}