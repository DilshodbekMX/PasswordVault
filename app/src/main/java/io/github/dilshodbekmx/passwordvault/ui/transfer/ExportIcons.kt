package io.github.dilshodbekmx.passwordvault.ui.transfer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The export screen's own icon: the back arrow of its top bar. The drawables of `res/drawable` belong
 * to their screen areas, so this one is drawn here, from the same path data as theirs: Google's
 * Material Icons "arrow_back" (Apache License 2.0). It is mirrored in a right-to-left layout. The
 * fill is only a placeholder: `Icon` tints it with the theme's content colour.
 */
object ExportIcons {
    val Back: ImageVector by lazy {
        ImageVector.Builder(
            name = "export_back",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).addPath(
            pathData = PathParser().parsePathString("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z").toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
    }
}
