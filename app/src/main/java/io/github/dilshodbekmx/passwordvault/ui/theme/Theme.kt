package io.github.dilshodbekmx.passwordvault.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dilshodbekmx.passwordvault.settings.AppSettings
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice
import io.github.dilshodbekmx.passwordvault.settings.ThemeChoice

/**
 * The app's look, around the whole navigation (architecture.md §3.6: `AppRoot` calls it with
 * `graph.settings`): the two choices of Settings › Appearance (ux.md §5.18;
 * docs/design/looks/README.md).
 *
 * - **Colours** (`AppSettings.style`): Bank blue, Emerald or Night vault, or the colours of the
 *   phone's wallpaper (Android 12 and later; Bank blue stands in for them where the phone has none).
 * - **Light or dark** (`AppSettings.theme`): System, Light or Dark. Every style has both schemes. The
 *   status and navigation bar icons follow the same choice, so a forced light theme on a phone in
 *   dark mode still has readable bar icons.
 *
 * Both are app settings outside the vault, read before unlock, so the lock screen has them too. A
 * change applies at once, without a restart, to every screen, dialog and sheet: they are all drawn
 * with `MaterialTheme.colorScheme` and [LocalLook], which this provides.
 *
 * Every text pair of the app's own schemes has a contrast of at least 4.5:1 and every outline that
 * carries meaning 3:1 (ux.md §8 "Contrast"; `SchemeContrastTest`). Russian text is hyphenated
 * (i18n.md §4.5 rule 4); in the other languages a word too long for its line breaks only at a hyphen
 * or a soft hyphen ([typographyFor]); Uzbek has no hyphenation patterns on Android.
 */
@Composable
fun PasswordVaultTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val theme by settings.theme.collectAsStateWithLifecycle()
    val style by settings.style.collectAsStateWithLifecycle()
    val dark = isDark(theme)
    SystemBarsFollow(dark)
    PasswordVaultLook(style, dark, content)
}

/**
 * The look of a fixed [style] and [theme], without touching the system bars: for previews and
 * tests, and for the previews of the styles in Settings › Appearance.
 */
@Composable
fun PasswordVaultTheme(style: StyleChoice, theme: ThemeChoice, content: @Composable () -> Unit) {
    PasswordVaultLook(style, isDark(theme), content)
}

/**
 * The default style, Bank blue, in a fixed [theme], without touching the system bars: for previews
 * and tests.
 */
@Composable
fun PasswordVaultTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    PasswordVaultLook(StyleChoice.DEFAULT, isDark(theme), content)
}

/** Whether [choice] means dark: System follows the phone. */
@Composable
fun isDark(choice: ThemeChoice): Boolean = when (choice) {
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
}

/**
 * The look of [style] in light or in [dark] on this phone, remembered: the wallpaper's colours are
 * read again when the configuration changes, which a new wallpaper causes.
 */
@Composable
fun rememberLook(style: StyleChoice, dark: Boolean): Look {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(style, dark, context, configuration) { Looks.shown(style, dark, context) }
}

@Composable
private fun PasswordVaultLook(style: StyleChoice, dark: Boolean, content: @Composable () -> Unit) {
    val look = rememberLook(style, dark)
    val language = LocalConfiguration.current.locales[0]?.language
    val typography = remember(language) { typographyFor(language) }
    CompositionLocalProvider(LocalLook provides look) {
        MaterialTheme(
            colorScheme = look.colors,
            typography = typography,
            content = content,
        )
    }
}

/**
 * Material 3's type scale, with hyphenation on in every language (i18n.md §4.5 rule 4):
 * - Russian: automatic hyphenation with paragraph line breaking, so that long words break inside
 *   narrow labels instead of overflowing them.
 * - The other languages keep Material's simple line breaking, in which only a word that does not fit
 *   on a line of its own is broken: with hyphenation on, it breaks after a hyphen ("PasswordVault- /
 *   backup") or at a soft hyphen (U+00AD) instead of between any two letters ("PasswordVault-backu /
 *   p"). Android has no Uzbek hyphenation patterns, so an Uzbek word without a hyphen still cannot
 *   break: the texts avoid such words (build plan §12, R6 question 2). Measured on API 37 (build plan
 *   8.1): with hyphenation off Android breaks neither after a hyphen nor at a soft hyphen.
 */
internal fun typographyFor(language: String?): Typography {
    val base = Typography()
    val russian = language == "ru"
    fun TextStyle.hyphenated() = if (russian) copy(hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph) else copy(hyphens = Hyphens.Auto)
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

/**
 * While this is composed, the status bar's icons are drawn for [colour], which the screen draws
 * behind the status bar: light icons on a dark colour, dark icons on a light one. When it leaves
 * the composition they are as they were, which is what the theme set ([SystemBarsFollow]). For
 * Bank blue's lock-screen panel (docs/design/looks/README.md "Lock screen"), whose blue reaches the
 * top of the screen also in a light theme, where the theme draws dark icons.
 */
@Composable
fun StatusBarIconsOver(colour: androidx.compose.ui.graphics.Color) {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return
    val lightBehind = colour.luminance() > HALF
    DisposableEffect(window, view, lightBehind) {
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = lightBehind
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

/** Above this relative luminance a colour is light: dark icons go on it. */
private const val HALF = 0.5f

/** androidx.activity's navigation bar scrims (`EdgeToEdge.kt`, internal there). */
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
