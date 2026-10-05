package com.hermes.desktop

import android.graphics.Rect

data class DesktopTask(
    val taskId: Int,
    val packageName: String,
    val componentName: String?,
    val bounds: Rect?,
    val visible: Boolean,
    val windowingMode: String,
    val focused: Boolean = false
)

enum class DesktopTaskAction {
    FOCUS,
    MINIMIZE,
    MAXIMIZE,
    RESTORE,
    SNAP_LEFT,
    SNAP_RIGHT,
    CLOSE
}

object DesktopTaskParser {

    private val displayRegex = Regex("""Display #(\d+)""")
    private val taskRegex = Regex("""\* Task\{[^#]*#(\d+)\b""")
    private val packageRegex = Regex("""\bA=([^\s}]+)""")
    private val modeRegex = Regex("""\bmode=([^\s}]+)""")
    private val visibleRegex = Regex("""\bvisible=(true|false)""")
    private val boundsRegex = Regex("""(?:mBounds=|bounds=)Rect\(([-\d]+),\s*([-\d]+)\s*-\s*([-\d]+),\s*([-\d]+)\)""")
    private val activityRegex = Regex("""([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)""")
    private val resumedRegex = Regex("""(?:mResumedActivity|topResumedActivity).*?\s([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)""")

    fun parse(raw: String, targetDisplayId: Int): List<DesktopTask> {
        if (raw.isBlank()) return emptyList()

        data class MutableTask(
            var taskId: Int,
            var packageName: String,
            var componentName: String? = null,
            var bounds: Rect? = null,
            var visible: Boolean = false,
            var windowingMode: String = "unknown",
            var focused: Boolean = false
        )

        val tasks = linkedMapOf<Int, MutableTask>()
        var currentDisplay = -1
        var currentTask: MutableTask? = null
        var focusedComponent: String? = null

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()

            displayRegex.find(line)?.let {
                currentDisplay = it.groupValues[1].toIntOrNull() ?: -1
                currentTask = null
                return@forEach
            }

            resumedRegex.find(line)?.let {
                focusedComponent = it.groupValues[1]
            }

            if (currentDisplay != targetDisplayId) return@forEach

            taskRegex.find(line)?.let { match ->
                val taskId = match.groupValues[1].toIntOrNull() ?: return@let
                val pkg = packageRegex.find(line)?.groupValues?.getOrNull(1).orEmpty()
                val task = tasks.getOrPut(taskId) {
                    MutableTask(taskId, pkg)
                }
                if (pkg.isNotBlank()) task.packageName = pkg
                modeRegex.find(line)?.groupValues?.getOrNull(1)?.let { task.windowingMode = it }
                visibleRegex.find(line)?.groupValues?.getOrNull(1)?.let { task.visible = it == "true" }
                currentTask = task
            }

            val task = currentTask ?: return@forEach

            boundsRegex.find(line)?.let { b ->
                task.bounds = Rect(
                    b.groupValues[1].toInt(),
                    b.groupValues[2].toInt(),
                    b.groupValues[3].toInt(),
                    b.groupValues[4].toInt()
                )
            }

            if (task.componentName == null) {
                activityRegex.find(line)?.groupValues?.getOrNull(1)?.let { component ->
                    if (!component.startsWith("android/") && component.contains('/')) {
                        task.componentName = component
                        if (task.packageName.isBlank()) {
                            task.packageName = component.substringBefore('/')
                        }
                    }
                }
            }
        }

        return tasks.values
            .filter { it.packageName.isNotBlank() }
            .map {
                val focused = focusedComponent?.startsWith(it.packageName + "/") == true ||
                    focusedComponent == it.componentName
                DesktopTask(
                    taskId = it.taskId,
                    packageName = it.packageName,
                    componentName = it.componentName,
                    bounds = it.bounds,
                    visible = it.visible,
                    windowingMode = it.windowingMode,
                    focused = focused
                )
            }
            .distinctBy { it.taskId }
            .sortedWith(
                compareByDescending<DesktopTask> { it.focused }
                    .thenByDescending { it.visible }
                    .thenByDescending { it.taskId }
            )
    }
}
