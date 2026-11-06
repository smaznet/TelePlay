package com.smaznet.teleplay

import android.app.Application
import com.smaznet.teleplay.ui.nav.AppContainer
import com.smaznet.teleplay.ui.nav.AppContainerProvider

/** Application entry; owns the manually wired [ServiceLocator] (no DI framework). */
class TelePlayApp : Application(), AppContainerProvider {
    lateinit var services: ServiceLocator
        private set

    override val appContainer: AppContainer get() = services

    override fun onCreate() {
        super.onCreate()
        services = ServiceLocator(this).also { it.start() }
    }
}
