package com.hermes.desktop

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

class AppRepository(private val context: Context) {
    fun launcherApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val results: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

        return results
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm)?.toString().orEmpty().ifBlank { it.activityInfo.packageName },
                    packageName = it.activityInfo.packageName,
                    icon = runCatching { it.loadIcon(pm) }.getOrNull()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
