package com.inkforge.notesstudio

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** All windows and backup jobs share the same ordered database writer. */
class NoteApplication : Application() {
    val repository by lazy { NoteRepository(this) }
    internal val windows = WindowClaims<MainActivity>()
    private val visible = AtomicInteger()
    internal val hasVisibleWindow get() = visible.get() > 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { visible.incrementAndGet() }
            override fun onActivityStopped(activity: Activity) { visible.decrementAndGet() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        BackupSchedule.restore(this)
    }
}

/** One editable window per document, including while its final saves are draining. */
internal class WindowClaims<T : Any> {
    private val owners = HashMap<String, WeakReference<T>>()
    @Synchronized fun claim(id: String, owner: T): T? {
        val existing = owners[id]?.get()
        if (existing != null && existing !== owner) return existing
        owners[id] = WeakReference(owner)
        return null
    }
    @Synchronized fun owner(id: String): T? = owners[id]?.get()
    @Synchronized fun release(id: String, owner: T) {
        if (owners[id]?.get() === owner) owners.remove(id)
    }
    @Synchronized fun releaseAll(owner: T) {
        owners.entries.removeAll { it.value.get() == null || it.value.get() === owner }
    }
}
