/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Application
import android.os.Handler
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.session.Session
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.concept.push.PushProcessor
import mozilla.components.feature.addons.update.GlobalAddonDependencyProvider
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.rusthttp.RustHttpConfig
import mozilla.components.support.rustlog.RustLog
import mozilla.components.support.webextensions.WebExtensionSupport
import org.mozilla.reference.browser.ext.isCrashReportActive
import org.mozilla.reference.browser.push.PushFxaIntegration
import org.mozilla.reference.browser.push.WebPushEngineIntegration
import org.mozilla.thirdparty.com.google.android.exoplayer2.util.TraceUtil

open class BrowserApplication : Application() {
    val components by lazy { Components(this) }

    val initializationDone = MutableLiveData(false)

    override fun onCreate() {
        TraceUtil.beginSection("App on create")
        super.onCreate()

        setupCrashReporting(this)

        RustHttpConfig.setClient(lazy { components.core.client })
        setupLogging()

        if (!isMainProcess()) {
            // If this is not the main process then do not continue with the initialization here. Everything that
            // follows only needs to be done in our app's main process and should not be done in other processes like
            // a GeckoView child process or the crash handling process. Most importantly we never want to end up in a
            // situation where we create a GeckoRuntime from the Gecko child process (
            return
        }

//        CoroutineScope(Dispatchers.Main).launch {
//        }
        Handler().postDelayed({
            setUp()
            initializationDone.value = true
        },100)

        TraceUtil.endSection()
    }

    private fun setUp() {

        components.core.engine.warmUp()

        GlobalAddonDependencyProvider.initialize(
                components.core.addonManager,
                components.core.addonUpdater
        )
        WebExtensionSupport.initialize(
                runtime = components.core.engine,
                store = components.core.store,
                onNewTabOverride = { _, engineSession, url ->
                    val session = Session(url)
                    components.core.sessionManager.add(session, true, engineSession)
                    session.id
                },
                onCloseTabOverride = { _, sessionId ->
                    components.useCases.tabsUseCases.removeTab(sessionId)
                },
                onSelectTabOverride = { _, sessionId ->
                    val selected = components.core.sessionManager.findSessionById(sessionId)
                    selected?.let { components.useCases.tabsUseCases.selectTab(it) }
                },
                onUpdatePermissionRequest = components.core.addonUpdater::onUpdatePermissionRequest
        )

        components.analytics.initializeGlean()
        components.analytics.initializeExperiments()

        components.push.feature?.let {
            Logger.info("AutoPushFeature is configured, initializing it...")

            PushProcessor.install(it)

            // WebPush integration to observe and deliver push messages to engine.
            WebPushEngineIntegration(components.core.engine, it).start()

            // Perform a one-time initialization of the account manager if a message is received.
            PushFxaIntegration(it, lazy { components.backgroundServices.accountManager }).launch()

            // Initialize the push feature and service.
            it.initialize()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runOnlyInMainProcess {
            components.core.store.dispatch(SystemAction.LowMemoryAction(level))
            components.core.icons.onTrimMemory(level)
        }
    }

    companion object {
        const val NON_FATAL_CRASH_BROADCAST = "org.mozilla.reference.browser"
    }
}

private fun setupLogging() {
    // We want the log messages of all builds to go to Android logcat
    Log.addSink(AndroidLogSink())
    RustLog.enable()
}

private fun setupCrashReporting(application: BrowserApplication) {
    if (isCrashReportActive) {
        application
            .components
            .analytics
            .crashReporter.install(application)
    }
}
