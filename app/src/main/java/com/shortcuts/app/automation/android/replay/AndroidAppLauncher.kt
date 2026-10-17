package com.shortcuts.app.automation.android.replay

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LaunchPolicy
import com.shortcuts.app.automation.model.LaunchTarget
import com.shortcuts.app.automation.replay.AppLauncher
import com.shortcuts.app.automation.replay.LaunchDispatchResult

fun launchFlagsForPolicy(policy: LaunchPolicy): Int = when (policy) {
    LaunchPolicy.RESET_TO_ROOT ->
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    LaunchPolicy.RESUME_EXISTING_TASK ->
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    LaunchPolicy.EXPLICIT_EXPORTED_ACTIVITY ->
        Intent.FLAG_ACTIVITY_NEW_TASK
    LaunchPolicy.DEEP_LINK ->
        Intent.FLAG_ACTIVITY_NEW_TASK
}

class AndroidAppLauncher(
    private val context: Context
) : AppLauncher {

    companion object {
        private const val TAG = "AndroidAppLauncher"
    }

    override suspend fun launch(step: LaunchAppStep): LaunchDispatchResult {
        return try {
            val intent = when (val target = step.target) {
                is LaunchTarget.Launcher -> {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(target.packageName)
                        ?: return LaunchDispatchResult.Rejected("No launch intent found for package '${target.packageName}'")
                    launchIntent.flags = launchFlagsForPolicy(step.policy)
                    launchIntent
                }
                is LaunchTarget.Component -> {
                    val component = ComponentName(target.packageName, target.className)
                    Intent().apply {
                        this.component = component
                        flags = launchFlagsForPolicy(step.policy)
                    }
                }
                is LaunchTarget.DeepLink -> {
                    Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)).apply {
                        setPackage(target.packageName)
                        flags = launchFlagsForPolicy(step.policy)
                    }
                }
            }

            context.startActivity(intent)
            LaunchDispatchResult.Accepted
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Activity not found launching ${step.target.javaClass.simpleName} for ${step.target.packageNameForLog()}")
            LaunchDispatchResult.Rejected("App not found")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception launching ${step.target.javaClass.simpleName} for ${step.target.packageNameForLog()}")
            LaunchDispatchResult.Rejected("The app refused to open")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${step.target.javaClass.simpleName} for ${step.target.packageNameForLog()}: ${e.javaClass.simpleName}")
            LaunchDispatchResult.Rejected("The app could not be opened")
        }
    }
}

/** Package name only: never log a target's URI or component details, which can carry private data. */
private fun com.shortcuts.app.automation.model.LaunchTarget.packageNameForLog(): String = when (this) {
    is com.shortcuts.app.automation.model.LaunchTarget.Launcher -> packageName
    is com.shortcuts.app.automation.model.LaunchTarget.Component -> packageName
    is com.shortcuts.app.automation.model.LaunchTarget.DeepLink -> packageName
}
