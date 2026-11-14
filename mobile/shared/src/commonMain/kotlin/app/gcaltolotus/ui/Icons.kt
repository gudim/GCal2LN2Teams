package app.gcaltolotus.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Несколько значков Material (пути из Material Icons, Apache 2.0). Свои, чтобы не тянуть
 * material-icons ради шести значков.
 */
internal object AppIcons {
    val Home: ImageVector = icon("Home", "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z")

    val Sync: ImageVector = icon(
        "Sync",
        "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57 " +
            "20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74" +
            "C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z",
    )

    val Account: ImageVector = icon(
        "Account",
        "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16" +
            "v-2c0,-2.66 -5.33,-4 -8,-4z",
    )

    val Tune: ImageVector = icon(
        "Tune",
        "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9z" +
            "M21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z",
    )

    val Back: ImageVector = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")

    val Warning: ImageVector = icon("Warning", "M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
