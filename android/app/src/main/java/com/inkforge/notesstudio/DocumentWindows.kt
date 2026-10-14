package com.inkforge.notesstudio

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

internal object DocumentWindows {
    private const val ACTION = "com.inkforge.notesstudio.OPEN_DOCUMENT"
    fun intent(context: Context, id: String): Intent = Intent(context, DocumentActivity::class.java)
        .setAction(ACTION).setData(Uri.Builder().scheme("badnote").authority("document").appendPath(id).build())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
        .apply { if (Build.VERSION.SDK_INT >= 24) addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) }

    fun documentId(intent: Intent?): String? {
        if (intent?.action != ACTION) return null
        val uri = intent.data ?: return null
        return uri.pathSegments.singleOrNull()?.takeIf {
            uri.scheme == "badnote" && uri.authority == "document" && it.isNotBlank()
        }
    }

    fun focus(activity: Activity) {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val task = manager.appTasks.firstOrNull { it.taskInfo.id == activity.taskId }
        task?.moveToFront()
    }
}

/** A document task keeps its identity while imports and library tabs open other windows. */
class DocumentActivity : MainActivity()
