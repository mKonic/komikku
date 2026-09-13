package eu.kanade.tachiyomi.debug.stress

import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.main.MainActivity
import java.lang.ref.WeakReference

/**
 * The screen stack the stress scenarios drive, registered by the activity that owns it. Held weakly, so a run never
 * keeps a finished activity alive.
 */
object StressHooks {

    @Volatile
    private var activityRef: WeakReference<MainActivity>? = null

    @Volatile
    private var navigatorRef: WeakReference<Navigator>? = null

    fun attach(activity: MainActivity, navigator: Navigator) {
        activityRef = WeakReference(activity)
        navigatorRef = WeakReference(navigator)
    }

    val activity: MainActivity?
        get() = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    val navigator: Navigator?
        get() = navigatorRef?.get()?.takeIf { activity != null }
}
