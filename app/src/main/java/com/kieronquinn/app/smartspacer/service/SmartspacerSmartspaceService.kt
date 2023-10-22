package com.kieronquinn.app.smartspacer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import com.kieronquinn.app.smartspacer.BuildConfig
import com.kieronquinn.app.smartspacer.ISmartspacerCrashListener
import com.kieronquinn.app.smartspacer.R
import com.kieronquinn.app.smartspacer.Smartspacer.Companion.PACKAGE_KEYGUARD
import com.kieronquinn.app.smartspacer.components.notifications.NotificationChannel
import com.kieronquinn.app.smartspacer.components.notifications.NotificationId
import com.kieronquinn.app.smartspacer.components.smartspace.MediaDataSmartspacerSession
import com.kieronquinn.app.smartspacer.components.smartspace.SystemSmartspacerSession
import com.kieronquinn.app.smartspacer.receivers.SafeModeReceiver
import com.kieronquinn.app.smartspacer.repositories.*
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceConfig
import com.kieronquinn.app.smartspacer.sdk.model.UiSurface
import com.kieronquinn.app.smartspacer.sdk.utils.applySecurity
import com.kieronquinn.app.smartspacer.utils.extensions.startForeground
import com.kieronquinn.app.smartspacer.utils.extensions.toSmartspaceConfig
import com.kieronquinn.app.smartspacer.utils.extensions.toSystemSmartspaceTargetEvent
import com.kieronquinn.app.smartspacer.utils.extensions.whenCreated
import com.kieronquinn.app.smartspacer.utils.smartspace.LifecycleSmartspaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.FileDescriptor
import java.io.PrintWriter
import kotlin.system.exitProcess
import android.app.smartspace.SmartspaceConfig as SystemSmartspaceConfig
import android.app.smartspace.SmartspaceSessionId as SystemSmartspaceSessionId
import android.app.smartspace.SmartspaceTarget as SystemSmartspaceTarget
import android.app.smartspace.SmartspaceTargetEvent as SystemSmartspaceTargetEvent

@SuppressLint("NewApi")
class SmartspacerSmartspaceService: LifecycleSmartspaceService() {

    companion object {
        val COMPONENT = ComponentName(
            BuildConfig.APPLICATION_ID,
            SmartspacerSmartspaceService::class.java.name
        )

        private val SAFE_MODE_PACKAGES = arrayOf(
            PACKAGE_KEYGUARD,
            "com.google.android.apps.nexuslauncher"
        )
    }

    private val sessions = HashMap<SystemSmartspaceSessionId, SystemSmartspacerSession>()
    private val mediaDataSessions = HashMap<SystemSmartspaceSessionId, MediaDataSmartspacerSession>()
    private val shizuku by inject<ShizukuServiceRepository>()
    private val notifications by inject<NotificationRepository>()
    private val systemSmartspace by inject<SystemSmartspaceRepository>()
    private val settings by inject<SmartspacerSettingsRepository>()
    private val pruneLock = Mutex()

    private var lastTargets = HashMap<String, List<SystemSmartspaceTarget>>()

    private val packageCrashes = callbackFlow {
        shizuku.runWithService {
            it.setCrashListener(object: ISmartspacerCrashListener.Stub() {
                override fun onPackageCrashed(packageName: String) {
                    trySend(packageName)
                }
            })
        }
        awaitClose {
            shizuku.runWithServiceIfAvailable {
                it.setCrashListener(null)
            }
        }
    }

    private fun SystemSmartspaceSessionId.getPackageName(): String? {
        val id = id ?: return null
        return if(id.contains(":")){
            id.split(":")[0]
        }else id
    }

    private suspend fun pruneSessions() {
        pruneLock.withLock {
            withContext(Dispatchers.IO) {
                pruneSessionsLocked()
                pruneMediaSessionsLocked()
            }
        }
    }

    private suspend fun pruneSessionsLocked() = runCatching {
        val duplicateSessions = sessions.entries
            .groupBy({ it.key.getPackageName() }) {
                it.value
            }
            .filter { it.value.size > 1 }
        duplicateSessions.forEach {
            //Destroy all but the newest session
            it.value.sortedByDescending { session ->
                session.createdAt
            }.drop(1).forEach { session ->
                session.onDestroy()
                shizuku.runWithService { service ->
                    service.destroySmartspaceSession(session.sessionId)
                }
                sessions.remove(session.sessionId)
            }
        }
    }

    private suspend fun pruneMediaSessionsLocked() = runCatching {
        val duplicateSessions = mediaDataSessions.entries
            .groupBy({ it.key.getPackageName() }) {
                it.value
            }
            .filter { it.value.size > 1 }
        duplicateSessions.forEach {
            //Destroy all but the newest session
            it.value.sortedByDescending { session ->
                session.createdAt
            }.drop(1).forEach { session ->
                session.onDestroy()
                shizuku.runWithService { service ->
                    service.destroySmartspaceSession(session.sessionId)
                }
                mediaDataSessions.remove(session.sessionId)
            }
        }
    }

    override fun onCreateSmartspaceSession(
        config: SystemSmartspaceConfig,
        sessionId: SystemSmartspaceSessionId
    ) {
        if(config.packageName == BuildConfig.APPLICATION_ID){
            //Feedback loop! Service has been force stopped & restarted too fast, reject
            onDestroySmartspaceSession(sessionId)
            return
        }
        systemSmartspace.notifyServiceRunning()
        whenCreated {
            onCreateSmartspaceSession(config.toSmartspaceConfig(), sessionId)
        }
    }

    private fun onCreateSmartspaceSession(
        config: SmartspaceConfig,
        sessionId: SystemSmartspaceSessionId,
        init: Boolean = false
    ) {
        if(config.uiSurface != UiSurface.MEDIA_DATA_MANAGER) {
            sessions[sessionId] = SystemSmartspacerSession(
                this@SmartspacerSmartspaceService,
                config,
                sessionId,
                ::onUpdate
            ).also {
                if(init) it.onResume()
            }
        }else{
            mediaDataSessions[sessionId] = MediaDataSmartspacerSession(
                this@SmartspacerSmartspaceService,
                config,
                sessionId,
                ::onMediaUpdate
            ).also {
                if(init) it.onResume()
            }
        }
        whenCreated {
            pruneSessions()
        }
        setHasUsedSetting()
    }

    private suspend fun onUpdate(sessionId: SystemSmartspaceSessionId, targets: List<SystemSmartspaceTarget>) {
        if(!sessions.contains(sessionId)) return
        pruneSessions()
        updateSmartspaceTargets(sessionId, targets).also {
            val id = sessionId.id
            if(BuildConfig.DEBUG && id != null) {
                lastTargets[id] = targets
            }
        }
    }

    private suspend fun onMediaUpdate(sessionId: SystemSmartspaceSessionId, targets: List<SystemSmartspaceTarget>) {
        if(!mediaDataSessions.contains(sessionId)) return
        pruneSessions()
        updateSmartspaceTargets(sessionId, targets)
    }

    override fun onDestroySmartspaceSession(sessionId: SystemSmartspaceSessionId) {
        //Media sessions for some reason should not be destroyed and will be handled by prune
        val session = sessions.remove(sessionId) ?: return
        session.onDestroy()
        lastTargets.remove(sessionId.id)
    }

    override fun onDestroy(sessionId: SystemSmartspaceSessionId) {
        //Media sessions for some reason should not be destroyed and will be handled by prune
        val session = sessions.remove(sessionId) ?: return
        session.onDestroy()
        lastTargets.remove(sessionId.id)
    }

    override fun notifySmartspaceEvent(
        sessionId: SystemSmartspaceSessionId,
        event: SystemSmartspaceTargetEvent
    ) {
        val session = sessions[sessionId] ?: mediaDataSessions[sessionId] ?: return
        session.notifySmartspaceEvent(event.toSystemSmartspaceTargetEvent())
    }

    override fun onRequestSmartspaceUpdate(sessionId: SystemSmartspaceSessionId) {
        val session = sessions[sessionId] ?: mediaDataSessions[sessionId] ?: return
        session.requestSmartspaceUpdate()
    }

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        super.dump(fd, writer, args)
        if(!BuildConfig.DEBUG) return
        with(writer){
            write("=== SMARTSPACE ===")
            write("\n")
            write("${sessions.size} sessions: ${sessions.keys.joinToString(", ") { it.id ?: "" }}")
            write("\n")
            write("${mediaDataSessions.size} media sessions: ${mediaDataSessions.keys.joinToString(", ") { it.id ?: "" }}")
            write("\n")
            lastTargets.forEach {
                write("Session: ${it.key}")
                write("\n")
                it.value.forEach { target ->
                    write(target.toString())
                    write("\n")
                }
                write("============")
                write("\n")
            }
            val homeTargets = systemSmartspace.homeTargets.value
            write("Home Targets (${homeTargets.size}):")
            write("\n")
            homeTargets.forEach {
                write(it.toString())
                write("\n")
            }
            val lockTargets = systemSmartspace.lockTargets.value
            write("Lock Targets (${lockTargets.size}):")
            write("\n")
            lockTargets.forEach {
                write(it.toString())
                write("\n")
            }
        }
    }

    /**
     *  Listens for crashes in the logcat via Shizuku, triggering safe mode if any of the packages
     *  in [SAFE_MODE_PACKAGES] crash.
     */
    private fun setupCrashListener() = whenCreated {
        packageCrashes.collect {
            if(SAFE_MODE_PACKAGES.contains(it)){
                triggerSafeMode(it)
            }
        }
    }

    /**
     *  Triggers safe mode for a given [crashedPackage]
     *
     *  - Clears the "temporary" Smartspace Service, if Shizuku is still available. If it's not,
     *  rather than add more delay, assume it's been longer than 60s and skip
     *
     *  - Starts [SafeModeReceiver] via a broadcast, which will show a notification in a different
     *  process informing the user Smartspacer has entered safe mode
     *
     *  - Kills this process entirely, by force. This disconnects Smartspacer from Smartspace
     *  immediately and will reset it to the system provider, which should resolve the crash.
     */
    private fun triggerSafeMode(crashedPackage: String) = whenCreated {
        systemSmartspace.resetService(true)
        sendBroadcast(Intent(
            this@SmartspacerSmartspaceService, SafeModeReceiver::class.java
        ).apply {
            applySecurity(this@SmartspacerSmartspaceService)
            putExtra(SafeModeReceiver.KEY_CRASHED_PACKAGE, crashedPackage)
        })
        exitProcess(0)
    }

    init {
        setupCrashListener()
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NotificationId.NATIVE_SERVICE, createNotification())
        notifications.cancelNotification(NotificationId.NATIVE_MODE)
        systemSmartspace.notifyServiceRunning()
        setHasUsedSetting()
    }

    private fun createNotification(): Notification {
        return notifications.showNotification(
            NotificationId.NATIVE_SERVICE,
            NotificationChannel.BACKGROUND_SERVICE
        ) {
            val notificationIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannel.BACKGROUND_SERVICE.id)
            }
            it.setContentTitle(getString(R.string.notification_title_background_service))
            it.setContentText(getString(R.string.notification_content_background_service))
            it.setSmallIcon(R.drawable.ic_notification)
            it.setOngoing(true)
            it.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NotificationId.NATIVE_SERVICE.ordinal,
                    notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            it.setTicker(getString(R.string.notification_title_background_service))
        }
    }

    private fun setHasUsedSetting() = whenCreated {
        settings.hasUsedNativeMode.set(true)
    }

}