package com.kieronquinn.app.smartspacer.sdk.provider

import android.app.Activity
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import androidx.annotation.RestrictTo
import androidx.core.os.bundleOf
import com.kieronquinn.app.smartspacer.sdk.model.Backup
import com.kieronquinn.app.smartspacer.sdk.model.CompatibilityState
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerTargetProvider.Companion
import com.kieronquinn.app.smartspacer.sdk.receivers.SmartspacerTargetUpdateReceiver
import com.kieronquinn.app.smartspacer.sdk.utils.getParcelableCompat
import com.kieronquinn.app.smartspacer.sdk.utils.getProviderInfo

/**
 *  [SmartspacerTargetProvider] is a [ContentProvider] that represents one or more
 *  [SmartspaceTarget]s.
 *
 *  Implement [getSmartspaceTargets] to return one or more targets that should be visible,
 *  [onDismiss] to react to the target being dismissed from the home screen, and call [notifyChange]
 *  or [Companion.notifyChange] to tell Smartspacer to refresh the targets. Implement [getConfig] to
 *  return a configuration for this provider, including title, description and icon.
 */
abstract class SmartspacerTargetProvider: BaseProvider() {

    companion object {
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val METHOD_GET = "get_targets"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val METHOD_GET_CONFIG = "get_targets_config"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val METHOD_DISMISS = "dismiss"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val METHOD_ON_REMOVED = "on_removed"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val METHOD_BACKUP = "backup"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val METHOD_RESTORE = "restore"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val RESULT_KEY_SMARTSPACE_TARGETS = "targets"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val EXTRA_TARGET_ID = "target_id"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val EXTRA_SMARTSPACER_ID = "smartspacer_id"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val EXTRA_DID_DISMISS = "did_dismiss"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val EXTRA_BACKUP = "backup"
        @RestrictTo(RestrictTo.Scope.LIBRARY)
        const val EXTRA_SUCCESS = "success"

        private fun findAuthority(
            context: Context,
            provider: Class<out SmartspacerTargetProvider>
        ): String {
            return context.packageManager.getProviderInfo(ComponentName(context, provider))
                .authority
        }

        /**
         *  Notify Smartspacer of a change to a given [provider], and that targets should be
         *  refreshed. Pass a [smartspacerId] to only refresh that provider, otherwise all providers
         *  of this type will be refreshed.
         */
        fun notifyChange(
            context: Context,
            provider: Class<out SmartspacerTargetProvider>,
            smartspacerId: String? = null
        ) {
            val authority = findAuthority(context, provider)
            notifyChange(context, authority, smartspacerId)
        }

        /**
         *  Notify Smartspacer of a change to a given [authority], and that targets should be
         *  refreshed. Pass a [smartspacerId] to only refresh that provider, otherwise all providers
         *  of this type will be refreshed.
         */
        fun notifyChange(
            context: Context,
            authority: String,
            smartspacerId: String? = null
        ) {
            val uri = Uri.Builder().apply {
                scheme("content")
                authority(authority)
                if(smartspacerId != null){
                    appendPath(smartspacerId)
                }
            }.build()
            context.contentResolver?.notifyChange(uri, null, 0)
        }
    }

    /**
     *  Helper method to call [Companion.notifyChange], for the current provider. Use this to
     *  trigger a change from a local method, such as [onDismiss]
     */
    protected fun notifyChange(smartspacerId: String? = null) {
        notifyChange(provideContext(), this::class.java, smartspacerId)
    }

    /**
     *  Return the [SmartspaceTarget]s for this provider. Return an empty list if no targets
     *  should be visible.
     *
     *  [smartspacerId]: The Smartspacer ID for the instance of this provider
     */
    abstract fun getSmartspaceTargets(smartspacerId: String): List<SmartspaceTarget>

    /**
     *  Return the [Config] for this provider. This provides what to show in the UI for this
     *  target in Smartspacer, as well as settings for configuration
     *
     *  [smartspacerId]: The Smartspacer ID for the instance of this provider, or null if a generic
     *  config should be returned
     */
    abstract fun getConfig(smartspacerId: String?): Config

    /**
     *  Called when the user dismisses a target. This is done by long pressing a target on the
     *  home screen, it cannot be done from the lock screen. You should remove the target with the
     *  given [targetId] from your local list, and then call [notifyChange]. It's up to you how
     *  to do this, and when/if it should be shown again.
     *
     *  Return `true` if the Target was dismissed, `false` if it could not or cannot be dismissed.
     *  Returning `false` will not hide the Dismiss option in all situations, but it will present a
     *  Toast message to the user informing it cannot be dismissed.
     *
     *  [smartspacerId]: The Smartspacer ID for the instance of this provider
     *  [targetId]: The ID of the target that has been dismissed
     */
    abstract fun onDismiss(smartspacerId: String, targetId: String): Boolean

    /**
     *  Called when the provider is removed for a given [smartspacerId]. If you have not
     *  enabled [Config.allowAddingMoreThanOnce], this will not be called.
     */
    open fun onProviderRemoved(smartspacerId: String) {
        //No-op by default
    }

    /**
     *  Serialize your Target to a [Backup]. Implement this to allow Smartspacer to backup your
     *  Target's data, which will be passed to [restoreBackup] during a restore.
     *
     *  This may be called multiple times per backup for different [smartspacerId]s.
     *
     *  **Note:** Even if you are unable to backup your Target, for example if it requires external
     *  data from a configurable app widget, consider returning a [Backup] with [Backup.name] set.
     *  This will be displayed to the user during the restoration, as a hint of what the Target was
     *  configured to do, so they can reconfigure it.
     */
    open fun createBackup(smartspacerId: String): Backup {
        return Backup()
    }

    /**
     *  Deserialize a [backup], and store data which is being restored from the backup for a given
     *  [smartspacerId].
     *
     *  This may be called multiple times per restore for different [smartspacerId]s.
     *
     *  **Note:** If you returned a blank [backup] or one without [backup.data] set during
     *  [createBackup], this method will not be called.
     */
    open fun restoreBackup(smartspacerId: String, backup: Backup): Boolean {
        return false
    }

    final override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        verifySecurity()
        return when(method){
            METHOD_GET -> {
                val smartspacerId = extras?.getString(EXTRA_SMARTSPACER_ID) ?: return null
                createSmartspaceTargetsBundle(smartspacerId)
            }
            METHOD_GET_CONFIG -> {
                val smartspacerId = extras?.getString(EXTRA_SMARTSPACER_ID)
                getConfig(smartspacerId).toBundle()
            }
            METHOD_DISMISS -> {
                val smartspacerId = extras?.getString(EXTRA_SMARTSPACER_ID) ?: return null
                val targetId = extras.getString(EXTRA_TARGET_ID) ?: return null
                val didDismiss = onDismiss(smartspacerId, targetId)
                bundleOf(EXTRA_DID_DISMISS to didDismiss)
            }
            METHOD_ON_REMOVED -> {
                val smartspacerId = extras?.getString(EXTRA_SMARTSPACER_ID) ?: return null
                onProviderRemoved(smartspacerId)
                null
            }
            METHOD_BACKUP -> {
                val smartspacerId = extras?.getString(EXTRA_SMARTSPACER_ID) ?: return null
                bundleOf(EXTRA_BACKUP to createBackup(smartspacerId).toBundle())
            }
            METHOD_RESTORE -> {
                val smartspacerId = extras?.getString(EXTRA_SMARTSPACER_ID) ?: return null
                val backup = extras.getBundle(EXTRA_BACKUP)?.let { Backup(it) } ?: return null
                bundleOf(EXTRA_SUCCESS to restoreBackup(smartspacerId, backup))
            }
            else -> null
        }
    }

    private fun createSmartspaceTargetsBundle(smartspacerId: String): Bundle {
        //For future-proofing, we convert targets to bundles so we're not bound to the current model
        val targets = ArrayList(getSmartspaceTargets(smartspacerId).map { it.toBundle() })
        return bundleOf(
            RESULT_KEY_SMARTSPACE_TARGETS to targets
        )
    }

    data class Config(
        /**
         *  The label for this provider.
         */
        val label: CharSequence,
        /**
         *  A short description for this provider.
         */
        val description: CharSequence,
        /**
         *  The icon for this provider.
         */
        val icon: Icon,
        /**
         *  Whether this provider supports being added to Smartspacer more than once. This adds
         *  complexity - you need to handle the provided Smartspacer Target ID to calls and
         *  respond accordingly. See the wiki for more details.
         */
        val allowAddingMoreThanOnce: Boolean = false,
        /**
         *  The intent for configuring this Target, if required. This can be opened from
         *  Smartspacer's settings, and is intended for editing settings such as which items to
         *  show.
         *
         *  It is not called during setup, see [setupActivity] for that.
         */
        val configActivity: Intent? = null,
        /**
         *  The intent which will be launched when this Target is added. You **must** finish this
         *  activity with [Activity.RESULT_OK] for adding the target to succeed.
         */
        val setupActivity: Intent? = null,
        /**
         *  How often an update call can be sent to [SmartspacerTargetUpdateReceiver]. Please note
         *  that this is a **MINIMUM**, updates are not guaranteed to be sent on time, every time.
         *
         *  This relies on generic update calls from Smartspace (when using the system) or
         *  Smartspacer (when using widgets), which happen approximately once a minute, or less when
         *  the device is in deep sleep.
         *
         *  Default is `0` (no updates will be sent)
         */
        val refreshPeriodMinutes: Int = 0,
        /**
         *  If set to true, this Target will receive update calls even when it is not
         *  currently being displayed on the Smartspace which is visible to the user. For example,
         *  the user has added your Target and they are on the home screen, but you returned
         *  an empty list of [SmartspaceTarget]s in [getSmartspaceTargets]. If set to false, you
         *  would not receive update calls, but this allows you to receive them in this scenario.
         *
         *  **Use with extreme caution, this may cause higher battery drain if combined with a
         *  low refresh period**.
         */
        val refreshIfNotVisible: Boolean = false,
        /**
         *  Whether this Target is compatible with the device. Use [CompatibilityState.Compatible]
         *  when the device is compatible with the target, or [CompatibilityState.Incompatible] when
         *  the device is not. You should provide a reason for incompatibility, if set to `null` a
         *  generic message will be shown instead. Please note that this state is not dynamically
         *  updated, if your target is only compatible at certain times, for example if it requires
         *  a permission be granted first, you should mark it as compatible and return a basic
         *  target prompting the user to open the plugin app's settings and grant the permission.
         *
         *  A good example of a reason would be "This target requires Android 13"
         */
        val compatibilityState: CompatibilityState = CompatibilityState.Compatible,
        /**
         *  The authority of this Target's associated widget provider, if applicable. Setting this
         *  means Smartspacer will automatically bind the widget info returned by that provider,
         *  and send updates to it.
         *
         *  **Important**: The class associated with this authority must be registered as a provider
         *  in the manifest, and should be stable. If the provider cannot be found or errors out,
         *  this target will fail to be added.
         */
        val widgetProvider: String? = null,
        /**
         *  The authority of this Target's associated notification provider, if applicable. Setting
         *  this means Smartspacer will automatically request for notification listener permissions
         *  if required, and send notifications from the packages specified in the provider to it.
         *  You can use this to prevent the need to roll your own Notification Listener Service, and
         *  not need to stay running all the time.
         */
        val notificationProvider: String? = null,
        /**
         *  The authority of this Target's associated broadcast provider, if applicable. Setting
         *  this means Smartspacer will automatically send broadcast events matching IntentFilters
         *  specified in the provider to it. You can use this to subscribe to implicit broadcast
         *  intents without needing to stay running all the time.
         */
        val broadcastProvider: String? = null
    ) {

        companion object {
            private const val KEY_LABEL = "label"
            private const val KEY_DESCRIPTION = "description"
            private const val KEY_ICON = "icon"
            private const val KEY_CONFIG_ACTIVITY = "config_activity"
            private const val KEY_SETUP_ACTIVITY = "setup_activity"
            private const val KEY_COMPATIBILITY_STATE = "compatibility_state"
            private const val KEY_ALLOW_ADDING_MORE_THAN_ONCE = "allow_adding_more_than_once"
            private const val KEY_REFRESH_PERIOD_MINUTES = "refresh_period_minutes"
            private const val KEY_REFRESH_IF_NOT_VISIBLE = "refresh_if_not_visible"
            private const val KEY_WIDGET_PROVIDER = "widget_provider"
            private const val KEY_NOTIFICATION_PROVIDER = "notification_provider"
            private const val KEY_BROADCAST_PROVIDER = "broadcast_provider"
        }

        @RestrictTo(RestrictTo.Scope.LIBRARY)
        constructor(bundle: Bundle): this(
            bundle.getCharSequence(KEY_LABEL)!!,
            bundle.getCharSequence(KEY_DESCRIPTION)!!,
            bundle.getParcelableCompat(KEY_ICON, Icon::class.java)!!,
            bundle.getBoolean(KEY_ALLOW_ADDING_MORE_THAN_ONCE),
            bundle.getParcelableCompat<Intent>(KEY_CONFIG_ACTIVITY, Intent::class.java),
            bundle.getParcelableCompat<Intent>(KEY_SETUP_ACTIVITY, Intent::class.java),
            bundle.getInt(KEY_REFRESH_PERIOD_MINUTES),
            bundle.getBoolean(KEY_REFRESH_IF_NOT_VISIBLE, false),
            CompatibilityState.fromBundle(bundle.getBundle(
                KEY_COMPATIBILITY_STATE
            )!!),
            bundle.getString(KEY_WIDGET_PROVIDER),
            bundle.getString(KEY_NOTIFICATION_PROVIDER),
            bundle.getString(KEY_BROADCAST_PROVIDER)
        )

        @RestrictTo(RestrictTo.Scope.LIBRARY)
        fun toBundle(): Bundle {
            return bundleOf(
                KEY_LABEL to label,
                KEY_DESCRIPTION to description,
                KEY_ICON to icon,
                KEY_ALLOW_ADDING_MORE_THAN_ONCE to allowAddingMoreThanOnce,
                KEY_COMPATIBILITY_STATE to compatibilityState.toBundle(),
                KEY_REFRESH_PERIOD_MINUTES to refreshPeriodMinutes,
                KEY_REFRESH_IF_NOT_VISIBLE to refreshIfNotVisible,
                KEY_SETUP_ACTIVITY to setupActivity,
                KEY_CONFIG_ACTIVITY to configActivity,
                KEY_WIDGET_PROVIDER to widgetProvider,
                KEY_NOTIFICATION_PROVIDER to notificationProvider,
                KEY_BROADCAST_PROVIDER to broadcastProvider
            )
        }

    }

}