package io.github.dilshodbekmx.passwordvault.ui.theme

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice

/**
 * The app's look, around the whole navigation (architecture.md §3.6: `AppRoot` calls it with
 * `graph.settings`). Light or dark as Settings › Theme says (`AppSettings.theme`: System, Light or
 * Dark; ux.md §5.18), and the status and navigation bar icons follow the same choice, so a forced
 * light theme on a phone in dark mode still has readable bar icons.
 *
 * The colours are Material 3's: the wallpaper-based scheme on Android 12 and later, a fixed scheme
 * before (ux.md §8 "Contrast"; Material 3 colour roles meet WCAG AA when used as pairs). Russian
 * text is hyphenated (i18n.md §4.5 rule 4); Uzbek has no hyphenation patterns on Android.
 */
@Composable
fun PasswordVaultTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val choice by settings.theme.collectAsStateWithLifecycle()
    val dark = isDark(choice)
    SystemBarsFollow(dark)
    PasswordVaultLook(dark, content)
}

/**
 * The same look for a fixed [theme], without touching the system bars: for previews and tests, and
 * for the few parts drawn outside the app's theme (the window kit's notice of an obscured touch).
 */
@Composable
fun PasswordVaultTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    PasswordVaultLook(isDark(theme), content)
}

/** Whether [choice] means dark: System follows the phone. */
@Composable
fun isDark(choice: ThemeChoice): Boolean = when (choice) {
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
}

/**
 * The colour scheme: on Android 12 and later the wallpaper-based one, before that Material 3's
 * baseline scheme.
 */
fun passwordVaultColorScheme(dark: Boolean, context: Context): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

@Composable
private fun PasswordVaultLook(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0]?.language
    val typography = remember(language) { typographyFor(language) }
    MaterialTheme(
        colorScheme = passwordVaultColorScheme(dark, context),
        typography = typography,
        content = content,
    )
}

/**
 * Material 3's type scale; for Russian with automatic hyphenation and paragraph line breaking, so
 * that long words break inside narrow labels instead of overflowing them (i18n.md §4.5).
 */
private fun typographyFor(language: String?): Typography {
    val base = Typography()
    if (language != "ru") return base
    fun TextStyle.hyphenated() = copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph)
    return base.copy(
        displayLarge = base.displayLarge.hyphenated(),
        displayMedium = base.displayMedium.hyphenated(),
        displaySmall = base.displaySmall.hyphenated(),
        headlineLarge = base.headlineLarge.hyphenated(),
        headlineMedium = base.headlineMedium.hyphenated(),
        headlineSmall = base.headlineSmall.hyphenated(),
        titleLarge = base.titleLarge.hyphenated(),
        titleMedium = base.titleMedium.hyphenated(),
        titleSmall = base.titleSmall.hyphenated(),
        bodyLarge = base.bodyLarge.hyphenated(),
        bodyMedium = base.bodyMedium.hyphenated(),
        bodySmall = base.bodySmall.hyphenated(),
        labelLarge = base.labelLarge.hyphenated(),
        labelMedium = base.labelMedium.hyphenated(),
        labelSmall = base.labelSmall.hyphenated(),
    )
}

/**
 * Light or dark bar icons for [dark], through `enableEdgeToEdge` (which `WindowPolicy.onCreate`
 * first called with the phone's own mode): transparent status bar, and on Android 9 and older the
 * same navigation bar scrims as androidx.activity's defaults.
 */
@Composable
private fun SystemBarsFollow(dark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, dark) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        onDispose { }
    }
}

/** androidx.activity's navigation bar scrims (`EdgeToEdge.kt`, internal there). */
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
