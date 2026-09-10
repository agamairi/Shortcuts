package com.shortcuts.app.util

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.UserHandle
import android.util.Log
import org.xmlpull.v1.XmlPullParser

data class ParsedShortcut(
    val id: String,
    val shortLabel: String,
    val action: String?,
    val targetPackage: String?,
    val data: String?,
    val targetClass: String?
)

class AppShortcutCatalog(private val context: Context) {

    fun getShortcuts(packageName: String): List<ParsedShortcut> {
        val staticShortcuts = getStaticShortcuts(packageName)
        val dynamicShortcuts = getDynamicShortcutsOpportunistically(packageName)

        // Merge, de-duplicating by id. Dynamic shortcuts override static if they share an id (Android behavior).
        val merged = mutableMapOf<String, ParsedShortcut>()
        for (shortcut in staticShortcuts) {
            merged[shortcut.id] = shortcut
        }
        for (shortcut in dynamicShortcuts) {
            merged[shortcut.id] = shortcut
        }
        return merged.values.toList()
    }

    internal fun getStaticShortcuts(packageName: String): List<ParsedShortcut> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }

        val resolveInfos = try {
            pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            return emptyList()
        }

        if (resolveInfos.isEmpty()) {
            return emptyList()
        }

        val activityInfo = resolveInfos.first().activityInfo
        val metaData = activityInfo.metaData ?: return emptyList()
        val resId = metaData.getInt("android.app.shortcuts", 0)
        if (resId == 0) {
            return emptyList()
        }

        val targetRes = try {
            pm.getResourcesForApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList()
        }

        val parser = targetRes.getXml(resId)
        return parseShortcutsXml(parser, targetRes)
    }

    internal fun parseShortcutsXml(parser: XmlResourceParser, res: Resources): List<ParsedShortcut> {
        val shortcuts = mutableListOf<ParsedShortcut>()
        try {
            var eventType = parser.eventType
            var currentId: String? = null
            var currentShortLabel: String? = null
            var currentAction: String? = null
            var currentTargetPackage: String? = null
            var currentData: String? = null
            var currentTargetClass: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (parser.name == "shortcut") {
                        currentId = null
                        currentShortLabel = null
                        currentAction = null
                        currentTargetPackage = null
                        currentData = null
                        currentTargetClass = null

                        for (i in 0 until parser.attributeCount) {
                            val attrName = parser.getAttributeName(i)
                            if (attrName == "shortcutId") {
                                currentId = parser.getAttributeValue(i)
                            } else if (attrName == "shortcutShortLabel") {
                                val value = parser.getAttributeValue(i)
                                currentShortLabel = resolveString(value, res)
                            }
                        }
                    } else if (parser.name == "intent") {
                        for (i in 0 until parser.attributeCount) {
                            val attrName = parser.getAttributeName(i)
                            when (attrName) {
                                "action" -> currentAction = parser.getAttributeValue(i)
                                "targetPackage" -> currentTargetPackage = parser.getAttributeValue(i)
                                "data" -> currentData = parser.getAttributeValue(i)
                                "targetClass" -> currentTargetClass = parser.getAttributeValue(i)
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (parser.name == "shortcut") {
                        if (currentId != null && currentShortLabel != null) {
                            shortcuts.add(
                                ParsedShortcut(
                                    id = currentId,
                                    shortLabel = currentShortLabel,
                                    action = currentAction,
                                    targetPackage = currentTargetPackage,
                                    data = currentData,
                                    targetClass = currentTargetClass
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Malformed XML or other parsing error, just return what we have or empty
        } finally {
            parser.close()
        }
        return shortcuts
    }

    private fun resolveString(value: String, res: Resources): String {
        if (value.startsWith("@")) {
            return try {
                val resId = value.substring(1).toInt()
                res.getString(resId)
            } catch (e: Exception) {
                value
            }
        }
        return value
    }

    private fun getDynamicShortcutsOpportunistically(packageName: String): List<ParsedShortcut> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return emptyList()
            try {
                if (launcherApps.hasShortcutHostPermission()) {
                    val query = LauncherApps.ShortcutQuery().apply {
                        setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                        setPackage(packageName)
                    }
                    val user = android.os.Process.myUserHandle()
                    val shortcuts = launcherApps.getShortcuts(query, user)
                    return shortcuts?.mapNotNull { info ->
                        if (info.isEnabled && info.shortLabel != null) {
                            val intent = info.intent
                            ParsedShortcut(
                                id = info.id,
                                shortLabel = info.shortLabel.toString(),
                                action = intent?.action,
                                targetPackage = intent?.getPackage() ?: intent?.component?.packageName,
                                data = intent?.dataString,
                                targetClass = intent?.component?.className
                            )
                        } else null
                    } ?: emptyList()
                }
            } catch (e: SecurityException) {
                // Expected when not launcher
            } catch (e: Exception) {
                // Ignore
            }
        }
        return emptyList()
    }
}
