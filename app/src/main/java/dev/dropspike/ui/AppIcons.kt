package dev.dropspike.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * The few Material icons we need that aren't in material-icons-core (the extended set would
 * add megabytes to an unminified APK). Paths are from Material Icons (Apache 2.0).
 */
object AppIcons {
    private fun svg(name: String, d: String) = materialIcon(name = name) {
        addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
    }

    val Wifi: ImageVector by lazy {
        svg("App.Wifi", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z")
    }

    val Trophy: ImageVector by lazy {
        svg("App.Trophy", "M19,5h-2V3H7v2H5C3.9,5 3,5.9 3,7v1c0,2.55 1.92,4.63 4.39,4.94c0.63,1.5 1.98,2.63 3.61,2.96V19H7v2h10v-2h-4v-3.1c1.63,-0.33 2.98,-1.46 3.61,-2.96C19.08,12.63 21,10.55 21,8V7C21,5.9 20.1,5 19,5zM5,8V7h2v3.82C5.84,10.4 5,9.3 5,8zM19,8c0,1.3 -0.84,2.4 -2,2.82V7h2V8z")
    }

    val Stop: ImageVector by lazy {
        materialIcon(name = "App.Stop") { materialPath { moveTo(6f, 6f); horizontalLineToRelative(12f); verticalLineToRelative(12f); horizontalLineTo(6f); close() } }
    }

    val Drop: ImageVector by lazy {
        materialIcon(name = "App.Drop") {
            materialPath {
                moveTo(12f, 2.5f)
                curveTo(12f, 2.5f, 5f, 10.6f, 5f, 15f)
                arcToRelative(7f, 7f, 0f, false, false, 14f, 0f)
                curveTo(19f, 10.6f, 12f, 2.5f, 12f, 2.5f)
                close()
            }
        }
    }

    val Gamepad: ImageVector by lazy {
        materialIcon(name = "App.Gamepad") {
            materialPath {
                moveTo(21f, 6f); horizontalLineTo(3f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(8f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(18f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(8f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
                moveTo(11f, 13f); horizontalLineTo(8f); verticalLineToRelative(3f); horizontalLineTo(6f); verticalLineToRelative(-3f)
                horizontalLineTo(3f); verticalLineToRelative(-2f); horizontalLineToRelative(3f); verticalLineTo(8f); horizontalLineToRelative(2f)
                verticalLineToRelative(3f); horizontalLineToRelative(3f); verticalLineToRelative(2f); close()
                moveTo(15.5f, 15f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f); close()
                moveTo(19.5f, 12f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f); close()
            }
        }
    }

    val Bolt: ImageVector by lazy {
        materialIcon(name = "App.Bolt") {
            materialPath {
                moveTo(11f, 21f); horizontalLineToRelative(-1f); lineToRelative(1f, -7f); horizontalLineTo(7.5f)
                curveToRelative(-0.88f, 0f, -0.33f, -0.75f, -0.31f, -0.78f)
                curveTo(8.48f, 10.94f, 10.42f, 7.54f, 13.01f, 3f); horizontalLineToRelative(1f); lineToRelative(-1f, 7f)
                horizontalLineToRelative(3.51f); curveToRelative(0.4f, 0f, 0.62f, 0.19f, 0.4f, 0.66f)
                curveTo(12.97f, 17.55f, 11f, 21f, 11f, 21f); close()
            }
        }
    }

    val Link: ImageVector by lazy {
        materialIcon(name = "App.Link") {
            materialPath {
                moveTo(3.9f, 12f)
                curveToRelative(0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f); horizontalLineToRelative(4f); verticalLineTo(7f); horizontalLineTo(7f)
                curveToRelative(-2.76f, 0f, -5f, 2.24f, -5f, 5f); reflectiveCurveToRelative(2.24f, 5f, 5f, 5f); horizontalLineToRelative(4f)
                verticalLineToRelative(-1.9f); horizontalLineTo(7f); curveToRelative(-1.71f, 0f, -3.1f, -1.39f, -3.1f, -3.1f); close()
                moveTo(8f, 13f); horizontalLineToRelative(8f); verticalLineToRelative(-2f); horizontalLineTo(8f); verticalLineToRelative(2f); close()
                moveTo(17f, 7f); horizontalLineToRelative(-4f); verticalLineToRelative(1.9f); horizontalLineToRelative(4f)
                curveToRelative(1.71f, 0f, 3.1f, 1.39f, 3.1f, 3.1f); reflectiveCurveToRelative(-1.39f, 3.1f, -3.1f, 3.1f)
                horizontalLineToRelative(-4f); verticalLineTo(17f); horizontalLineToRelative(4f)
                curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f); reflectiveCurveToRelative(-2.24f, -5f, -5f, -5f); close()
            }
        }
    }

    val Schedule: ImageVector by lazy {
        materialIcon(name = "App.Schedule") {
            materialPath {
                moveTo(11.99f, 2f)
                curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f); reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f); reflectiveCurveTo(17.52f, 2f, 11.99f, 2f); close()
                moveTo(12f, 20f)
                curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f); reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
                reflectiveCurveToRelative(8f, 3.58f, 8f, 8f); reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f); close()
                moveTo(12.5f, 7f); horizontalLineTo(11f); verticalLineToRelative(6f); lineToRelative(5.25f, 3.15f)
                lineToRelative(0.75f, -1.23f); lineToRelative(-4.5f, -2.67f); close()
            }
        }
    }
}
