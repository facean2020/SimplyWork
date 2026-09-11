package com.yangz3720.simplywork

import android.app.Application

/**
 * Application entry: builds the composition root once per process.
 */
class SimplyWorkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}