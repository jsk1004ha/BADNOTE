package com.inkforge.notesstudio

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** One owner for the entire screen, including modals and the busy overlay. */
object RootSafeArea {
    fun install(root: View) {
        val extra = (8 * root.resources.displayMetrics.density).roundToInt()
        root.setPadding(0, extra, 0, extra)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top + extra, safe.right, safe.bottom + extra)
            WindowInsetsCompat.CONSUMED
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { ViewCompat.requestApplyInsets(view) }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}
