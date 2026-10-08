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
import androidx.compose.ui.graphics.Shape
import io.github.dilshodbekmx.passwordvault.settings.StyleChoice

/**
 * What the app is drawn with (docs/design/looks/README.md): a style's colour scheme in light or in
 * dark, and the few details that differ between the styles besides colour (the README's table "What
 * differs between styles besides colour"). A screen reads them from the theme, [LocalLook], and
 * never asks which style is chosen; the colours are also `MaterialTheme.colorScheme`.
 *
 * [pinKeys], [fingerprintKeyTonal], [avatarShape] and [avatarTones] are drawn today (the PIN pad, the
 * list). [lockPanel], [cards] and [addButton] are the table's other rows, for the screens that own
 * the lock screen, the list and the account's screen: the looks' second step (build-plan.md §12,
 * `w5-carry`).
 *
 * @property colors the Material 3 colour scheme.
 * @property pinKeys how a digit key of the PIN pad is drawn.
 * @property fingerprintKeyTonal the pad's fingerprint key is a tonal circle; otherwise it has no
 *   fill and its icon has the primary colour.
 * @property avatarShape the shape of an account's badge in the list.
 * @property avatarTones the badge's [AVATAR_TONES] tones.
 * @property lockPanel the lock screen's icon, name, subtitle and dots sit on a panel in the primary
 *   colour with rounded lower corners.
 * @property cards which rows of the list and of an account's screen lie on cards.
 * @property addButton how the list's "Add" button is drawn.
 */
@Immutable
class Look(
    val colors: ColorScheme,
    val pinKeys: PinKeyLook,
    val fingerprintKeyTonal: Boolean,
    val avatarShape: Shape,
    val avatarTones: List<AvatarTone>,
    val lockPanel: Boolean,
    val cards: CardsLook,
    val addButton: AddButtonLook,
) {
    /** The same details with other colours: a style's other mode, or the wallpaper's scheme. */
    internal fun copy(
        colors: ColorScheme = this.colors,
        avatarTones: List<AvatarTone> = this.avatarTones,
        lockPanel: Boolean = this.lockPanel,
    ): Look = Look(colors, pinKeys, fingerprintKeyTonal, avatarShape, avatarTones, lockPanel, cards, addButton)
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
        lockPanel = true,
        cards = CardsLook.NONE,
        addButton = AddButtonLook.TONAL,
    )
    private val bankBlueDark = bankBlueLight.copy(colors = BankBlueDark, avatarTones = BankBlueDarkTones)

    private val emeraldLight = Look(
        colors = EmeraldLight,
        pinKeys = PinKeyLook.PLAIN,
        fingerprintKeyTonal = true,
        avatarShape = RoundedCornerShape(percent = AVATAR_SQUARE_CORNER),
        avatarTones = EmeraldLightTones,
        lockPanel = false,
        cards = CardsLook.ROW_GROUPS,
        addButton = AddButtonLook.FILLED_ROUND,
    )
    private val emeraldDark = emeraldLight.copy(colors = EmeraldDark, avatarTones = EmeraldDarkTones)

    private val nightVaultDark = Look(
        colors = NightVaultDark,
        pinKeys = PinKeyLook.OUTLINED,
        fingerprintKeyTonal = false,
        avatarShape = CircleShape,
        avatarTones = NightVaultDarkTones,
        lockPanel = false,
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
            lockPanel = false,
        )

    /** The look to draw [style] with on this phone: [phone] where the phone has colours of its own, else [of]. */
    fun shown(style: StyleChoice, dark: Boolean, context: Context): Look =
        if (style == StyleChoice.PHONE_COLOURS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) phone(context, dark) else of(style, dark)
}

/** A square badge's corner, in per cent of its side: 12 dp of 40 dp. */
private const val AVATAR_SQUARE_CORNER = 30
