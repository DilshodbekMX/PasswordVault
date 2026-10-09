package io.github.dilshodbekmx.passwordvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dilshodbekmx.passwordvault.R

/**
 * The app's logo on Welcome and on the lock screens (ux.md §5.1, §5.9; docs/design/looks/README.md
 * "The icon and the name"): the launcher icon's drawing, `ic_app_logo.xml` (the shield with its
 * keyhole, which `docs/design/looks/icon/make-icon.py` writes from `icon.svg`), on a tile with
 * rounded corners, as a launcher shows the icon. The tile has the style's primary colour and the
 * shield the colour made to lie on it, so every style draws it in its own colours; Bank blue's
 * lock-screen panel gives its own ([tile], [mark], [ring]). It only decorates: TalkBack does not
 * read it, and the title beside it names the app.
 *
 * @param size the tile's side; the shield is 5/8 of it, as in the mock-up (40 dp of 64 dp).
 * @param ring a hairline just inside the tile's edge; null: none.
 */
@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Dp = LOGO_SIZE,
    tile: Color = MaterialTheme.colorScheme.primary,
    mark: Color = MaterialTheme.colorScheme.onPrimary,
    ring: Color? = null,
) {
    val shape = RoundedCornerShape(percent = TILE_CORNER_PERCENT)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(tile)
            .then(if (ring != null) Modifier.border(RING_WIDTH, ring, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = null,
            tint = mark,
            modifier = Modifier.size(size * MARK_SHARE),
        )
    }
}

/** The logo's side on the lock screens (ux.md §5.9). */
val LOGO_SIZE = 64.dp

/** The tile's corner, in per cent of its side: 18 dp of 64 dp, as in the mock-up. */
private const val TILE_CORNER_PERCENT = 28

/** How much of the tile the shield takes: 40 dp of 64 dp. */
private const val MARK_SHARE = 0.625f

private val RING_WIDTH = 2.dp
