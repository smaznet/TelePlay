package com.smaznet.teleplay.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's icons: outlined, 24-unit grid, 2-unit round stroke, one weight throughout. Drawn here so no icon
 * library is needed. The stroke colour is a placeholder; `Icon` tints them with the surrounding content colour,
 * so they follow the capsule buttons' rest and focused states.
 */
object TeleIcons {
    private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private fun ImageVector.Builder.stroked(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    private fun androidx.compose.ui.graphics.vector.PathBuilder.shieldOutline() {
        moveTo(12f, 3f)
        lineTo(19f, 6f)
        verticalLineTo(11f)
        curveTo(19f, 15.5f, 16f, 19f, 12f, 21f)
        curveTo(8f, 19f, 5f, 15.5f, 5f, 11f)
        verticalLineTo(6f)
        close()
    }

    /** Proxy off. */
    val Shield: ImageVector = icon("Shield") { stroked { shieldOutline() } }

    /** Proxy on: the same shield, checked. */
    val ShieldCheck: ImageVector = icon("ShieldCheck") {
        stroked { shieldOutline() }
        stroked { moveTo(9f, 12f); lineTo(11f, 14f); lineTo(15f, 10f) }
    }

    /** Settings: a cog with eight teeth around a round hub. */
    val Settings: ImageVector = icon("Settings") {
        stroked {
            val teeth = 8
            val inner = 7.0
            val outer = 9.5
            fun x(r: Double, deg: Double) = (12 + r * cos(Math.toRadians(deg))).toFloat()
            fun y(r: Double, deg: Double) = (12 + r * sin(Math.toRadians(deg))).toFloat()
            for (i in 0 until teeth) {
                val a = i * 360.0 / teeth
                val points = listOf(inner to a - 13, outer to a - 8, outer to a + 8, inner to a + 13)
                points.forEachIndexed { k, (r, d) -> if (i == 0 && k == 0) moveTo(x(r, d), y(r, d)) else lineTo(x(r, d), y(r, d)) }
            }
            close()
        }
        stroked {
            moveTo(15f, 12f)
            arcTo(3f, 3f, 0f, true, true, 9f, 12f)
            arcTo(3f, 3f, 0f, true, true, 15f, 12f)
            close()
        }
    }

    /** Refresh: almost a full circle with an arrowhead at the top right. */
    val Refresh: ImageVector = icon("Refresh") {
        stroked {
            moveTo(20f, 12f)
            arcTo(8f, 8f, 0f, true, true, 17.66f, 6.34f)
        }
        stroked { moveTo(18f, 2.5f); verticalLineTo(6.5f); horizontalLineTo(14f) }
    }
}
