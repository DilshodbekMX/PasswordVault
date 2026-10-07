package io.github.dilshodbekmx.passwordvault.data

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import io.github.dilshodbekmx.passwordvault.i18n.AppLanguages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The locale the screens are shown in, for the order and the letter headers of the list
 * ([ListModelProvider]; architecture.md §5.1, i18n.md §2.7): `AppLanguages.screenLocale` of the
 * configuration of the app's activity.
 *
 * A language change recreates the activity and nothing else (i18n.md §1.4.3, lock.md §11), so the
 * new locale is read from each activity as it is created, started and resumed. The activity's own
 * configuration is the one to read: on Android 7 to 12 AppCompat gives the chosen language to its
 * activities only, and the application context keeps the phone's (i18n.md §1.4.3). Before the first
 * activity the application's configuration stands in; nothing is listed before an activity exists,
 * because unlocking needs one.
 */
internal class ScreenLanguage private constructor(initial: Locale) : Application.ActivityLifecycleCallbacks {
    private val current = MutableStateFlow(initial)

    /** The locale the screens are shown in now. */
    val locale: StateFlow<Locale> = current.asStateFlow()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = follow(activity)

    override fun onActivityStarted(activity: Activity) = follow(activity)

    override fun onActivityResumed(activity: Activity) = follow(activity)

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun follow(activity: Activity) {
        current.value = localeOf(activity.resources.configuration)
    }

    companion object {
        /** Starts following the activities of [app]; one per process. */
        fun watch(app: Application): ScreenLanguage =
            ScreenLanguage(localeOf(app.resources.configuration)).also { app.registerActivityLifecycleCallbacks(it) }

        private fun localeOf(configuration: Configuration): Locale = AppLanguages.screenLocale(AppLanguages.localesOf(configuration))
    }
}
