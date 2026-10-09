package io.github.dilshodbekmx.passwordvault.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice

/**
 * What the app is drawn with (docs/design/looks/README.md): a style's colour scheme in light or in
 * dark, and the few details that differ between the styles besides colour (the README's table "What
 * differs between styles besides colour"). A screen reads them from the theme, [LocalLook], and
 * never asks which style is chosen; the colours are also `MaterialTheme.colorScheme`.
 *
 * [pinKeys], [fingerprintKeyTonal], [avatarShape] and [avatarTones] are drawn by the PIN pad and the
 * list (4.3b). [lockPanel], [cards] and [addButton] are the table's other rows, and [barOutline] and
 * [searchOutline] the two outlines of its colour table, drawn by the lock screen, the list, the
 * account's screen and the frame of the tabs: the looks' second step (build-plan.md §12, `w5-carry`).
 *
 * @property colors the Material 3 colour scheme.
 * @property pinKeys how a digit key of the PIN pad is drawn.
 * @property fingerprintKeyTonal the pad's fingerprint key is a tonal circle; otherwise it has no
 *   fill and its icon has the primary colour.
 * @property avatarShape the shape of an account's badge in the list.
 * @property avatarTones the badge's [AVATAR_TONES] tones.
 * @property lockPanel the lock screen's logo, name, subtitle and dots sit on a panel of these colours
 *   (Bank blue only); null: they lie on the screen.
 * @property cards which rows of the list and of an account's screen lie on cards.
 * @property addButton how the list's "Add" button is drawn.
 * @property barOutline the colour of a hairline along the navigation bar's upper edge, where the bar
 *   has nearly the colour of the screen behind it (Emerald in light: white on the tinted screen);
 *   null: none is drawn.
 * @property searchOutline the colour of the search field's outline, for the same reason; null: none.
 */
@Immutable
class Look(
    val colors: ColorScheme,
    val pinKeys: PinKeyLook,
    val fingerprintKeyTonal: Boolean,
    val avatarShape: Shape,
    val avatarTones: List<AvatarTone>,
    val lockPanel: LockPanel?,
    val cards: CardsLook,
    val addButton: AddButtonLook,
    val barOutline: Color? = null,
    val searchOutline: Color? = null,
) {
    /**
     * The same details with other colours: a style's other mode, or the wallpaper's scheme. The two
     * outlines belong to one scheme's colours, so they are given again or left out.
     */
    internal fun copy(
        colors: ColorScheme = this.colors,
        avatarTones: List<AvatarTone> = this.avatarTones,
        lockPanel: LockPanel? = this.lockPanel,
    ): Look = Look(colors, pinKeys, fingerprintKeyTonal, avatarShape, avatarTones, lockPanel, cards, addButton)
}

/**
 * Bank blue's lock-screen panel (docs/design/looks/README.md "Lock screen"): the panel behind the
 * lock screen's logo, name, subtitle and dots, and the colours of what lies on it. The mock-up shows
 * it in light, where the panel is the primary colour; in the dark scheme the same blue, #1D4E89, is
 * `primaryContainer`.
 *
 * @property colour the panel.
 * @property content the name, the line of a vault put aside and the filled dots.
 * @property quiet the subtitle "Enter your app PIN", a step quieter than [content].
 * @property ring an empty dot's ring.
 * @property logoTile the logo's tile on the panel, a lighter blue than the panel (the mock-up's);
 *   the shield on it is [content], inside a ring of [content] at a quarter ([logoRing]).
 */
@Immutable
class LockPanel(val colour: Color, val content: Color, val quiet: Color, val ring: Color, val logoTile: Color) {
    /** The logo's hairline on the panel: [content] at a quarter, as in the mock-up. */
    val logoRing: Color get() = content.copy(alpha = LOGO_RING_ALPHA)
}

/** A digit key of the PIN pad. */
enum class PinKeyLook {
    /** A filled circle. */
    FILLED,

    /** No fill and no outline: the digit alone. */
    PLAIN,

    /** An outlined circle. */
    OUTLINED,
}

/** Which rows lie on cards. */
enum class CardsLook {
    /** None: the rows of the list and of an account's screen lie on the background. */
    NONE,

    /** The rows of the list and of an account's screen are grouped on cards of radius 20 dp. */
    ROW_GROUPS,

    /** The password block of an account's screen lies on a card; every other row on the background. */
    PASSWORD_BLOCK,
}

/** The list's "Add" button. */
enum class AddButtonLook {
    /** Tonal: the primary container's colours. */
    TONAL,

    /** Filled: the primary colour. */
    FILLED,

    /** Filled and fully rounded. */
    FILLED_ROUND,
}

/**
 * The look the screens are drawn with: `PasswordVaultTheme` provides it. Outside the theme (a
 * preview, a test that composes a part alone) it is Bank blue in light.
 */
val LocalLook = staticCompositionLocalOf { Looks.of(StyleChoice.DEFAULT, dark = false) }

/** The looks of the styles of Settings › Appearance › "Colours". */
object Looks {
    private val bankBlueLight = Look(
        colors = BankBlueLight,
        pinKeys = PinKeyLook.FILLED,
        fingerprintKeyTonal = false,
        avatarShape = CircleShape,
        avatarTones = BankBlueLightTones,
        lockPanel = LockPanel(
            colour = BankBlueLight.primary,
            content = BankBlueLight.onPrimary,
            quiet = BankBlueLight.primaryContainer,
            ring = BankBlueLight.inversePrimary,
            logoTile = BankBluePanelTile,
        ),
        cards = CardsLook.NONE,
        addButton = AddButtonLook.TONAL,
    )
    private val bankBlueDark = bankBlueLight.copy(
        colors = BankBlueDark,
        avatarTones = BankBlueDarkTones,
        lockPanel = LockPanel(
            colour = BankBlueDark.primaryContainer,
            content = BankBlueDark.onPrimaryContainer,
            quiet = BankBlueDark.primary,
            ring = BankBlueDark.primary,
            logoTile = BankBluePanelTile,
        ),
    )

    private val emeraldLight = Look(
        colors = EmeraldLight,
        pinKeys = PinKeyLook.PLAIN,
        fingerprintKeyTonal = true,
        avatarShape = RoundedCornerShape(percent = AVATAR_SQUARE_CORNER),
        avatarTones = EmeraldLightTones,
        lockPanel = null,
        cards = CardsLook.ROW_GROUPS,
        addButton = AddButtonLook.FILLED_ROUND,
        // The README's "#FFFFFF with an outline #DCE5E1" and "#FFFFFF with an outline #BEC9C5".
        barOutline = EmeraldLight.surfaceVariant,
        searchOutline = EmeraldLight.outlineVariant,
    )
    private val emeraldDark = emeraldLight.copy(colors = EmeraldDark, avatarTones = EmeraldDarkTones)

    private val nightVaultDark = Look(
        colors = NightVaultDark,
        pinKeys = PinKeyLook.OUTLINED,
        fingerprintKeyTonal = false,
        avatarShape = CircleShape,
        avatarTones = NightVaultDarkTones,
        lockPanel = null,
        cards = CardsLook.PASSWORD_BLOCK,
        addButton = AddButtonLook.FILLED,
    )
    private val nightVaultLight = nightVaultDark.copy(colors = NightVaultLight, avatarTones = NightVaultLightTones)

    /**
     * The look of [style] in light or in [dark]. The three styles of the app's own are fixed colours.
     * For [StyleChoice.PHONE_COLOURS] this is Bank blue, which stands in for it wherever the phone has
     * no colours of its own; [phone] is the real one.
     */
    fun of(style: StyleChoice, dark: Boolean): Look = when (style) {
        StyleChoice.BANK_BLUE, StyleChoice.PHONE_COLOURS -> if (dark) bankBlueDark else bankBlueLight
        StyleChoice.EMERALD -> if (dark) emeraldDark else emeraldLight
        StyleChoice.NIGHT_VAULT -> if (dark) nightVaultDark else nightVaultLight
    }

    /**
     * "Phone's colours" (Android 12 and later): Material 3's scheme from the wallpaper, with Bank
     * blue's details but a plain lock screen (the README's table).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun phone(context: Context, dark: Boolean): Look =
        of(StyleChoice.BANK_BLUE, dark).copy(
            colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
            lockPanel = null,
        )

    /** The look to draw [style] with on this phone: [phone] where the phone has colours of its own, else [of]. */
    fun shown(style: StyleChoice, dark: Boolean, context: Context): Look =
        if (style == StyleChoice.PHONE_COLOURS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) phone(context, dark) else of(style, dark)
}

/** A square badge's corner, in per cent of its side: 12 dp of 40 dp. */
private const val AVATAR_SQUARE_CORNER = 30

/** The mock-up's ring of the logo on the panel: white at 25 per cent. */
private const val LOGO_RING_ALPHA = 0.25f
