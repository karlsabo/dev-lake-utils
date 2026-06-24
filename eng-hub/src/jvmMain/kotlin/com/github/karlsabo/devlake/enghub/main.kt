package com.github.karlsabo.devlake.enghub

import androidx.compose.ui.window.application
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

private const val DOCK_ICON_SIZE = 256
private const val ICON_GRID_UNITS = 32.0
private const val ICON_MARGIN_UNITS = 1.0
private const val ICON_BODY_SIZE_UNITS = 30.0
private const val ICON_CORNER_ARC_UNITS = 12.0
private const val BORDER_STROKE_WIDTH_UNITS = 1.0
private const val LINE_STROKE_WIDTH_UNITS = 2.0
private const val BASELINE_START_X_UNITS = 6.0
private const val BASELINE_Y_UNITS = 22.0
private const val BASELINE_END_X_UNITS = 26.0
private const val BAR_WIDTH_UNITS = 2.0
private const val WINDOW_DOT_Y_UNITS = 7.0
private const val WINDOW_DOT_RADIUS_UNITS = 1.5
private const val DATA_POINT_RADIUS_UNITS = 1.4
private const val CLOSE_DOT_X_UNITS = 7.0
private const val MINIMIZE_DOT_X_UNITS = 11.0
private const val ZOOM_DOT_X_UNITS = 15.0
private const val FIRST_BAR_X_UNITS = 9.0
private const val FIRST_BAR_Y_UNITS = 22.0
private const val FIRST_BAR_HEIGHT_UNITS = 4.0
private const val SECOND_BAR_X_UNITS = 13.0
private const val SECOND_BAR_Y_UNITS = 21.0
private const val SECOND_BAR_HEIGHT_UNITS = 5.0
private const val THIRD_BAR_X_UNITS = 17.0
private const val THIRD_BAR_Y_UNITS = 20.0
private const val THIRD_BAR_HEIGHT_UNITS = 6.0
private const val FOURTH_BAR_X_UNITS = 21.0
private const val FOURTH_BAR_Y_UNITS = 19.0
private const val FOURTH_BAR_HEIGHT_UNITS = 7.0
private const val FIRST_CHART_X_UNITS = 7.0
private const val FIRST_CHART_Y_UNITS = 20.0
private const val SECOND_CHART_X_UNITS = 12.0
private const val SECOND_CHART_Y_UNITS = 17.0
private const val THIRD_CHART_X_UNITS = 17.0
private const val THIRD_CHART_Y_UNITS = 18.0
private const val FOURTH_CHART_X_UNITS = 22.0
private const val FOURTH_CHART_Y_UNITS = 14.0
private const val FIFTH_CHART_X_UNITS = 25.0
private const val FIFTH_CHART_Y_UNITS = 12.0
private const val BACKGROUND_RGB = 0x1E232D
private const val BORDER_RGB = 0x46505F
private const val CLOSE_DOT_RGB = 0xFF5F55
private const val MINIMIZE_DOT_RGB = 0xFFBE3C
private const val SUCCESS_RGB = 0x3CC878
private const val BASELINE_ARGB = 0x2EFFFFFF
private const val CHART_LINE_RGB = 0x50A0FF
private const val DATA_POINT_RGB = 0xF5F7FA

private val windowDots = listOf(
    IconDot(CLOSE_DOT_X_UNITS, WINDOW_DOT_Y_UNITS, Color(CLOSE_DOT_RGB)),
    IconDot(MINIMIZE_DOT_X_UNITS, WINDOW_DOT_Y_UNITS, Color(MINIMIZE_DOT_RGB)),
    IconDot(ZOOM_DOT_X_UNITS, WINDOW_DOT_Y_UNITS, Color(SUCCESS_RGB)),
)

private val bars = listOf(
    IconBar(FIRST_BAR_X_UNITS, FIRST_BAR_Y_UNITS, FIRST_BAR_HEIGHT_UNITS),
    IconBar(SECOND_BAR_X_UNITS, SECOND_BAR_Y_UNITS, SECOND_BAR_HEIGHT_UNITS),
    IconBar(THIRD_BAR_X_UNITS, THIRD_BAR_Y_UNITS, THIRD_BAR_HEIGHT_UNITS),
    IconBar(FOURTH_BAR_X_UNITS, FOURTH_BAR_Y_UNITS, FOURTH_BAR_HEIGHT_UNITS),
)

private val chartPoints = listOf(
    IconPoint(FIRST_CHART_X_UNITS, FIRST_CHART_Y_UNITS),
    IconPoint(SECOND_CHART_X_UNITS, SECOND_CHART_Y_UNITS),
    IconPoint(THIRD_CHART_X_UNITS, THIRD_CHART_Y_UNITS),
    IconPoint(FOURTH_CHART_X_UNITS, FOURTH_CHART_Y_UNITS),
    IconPoint(FIFTH_CHART_X_UNITS, FIFTH_CHART_Y_UNITS),
)

fun main() {
    System.setProperty("apple.awt.application.name", ENG_HUB_DISPLAY_NAME)
    setDockIcon()
    application {
        EngHub(onExitApplication = ::exitApplication)
    }
}

private fun setDockIcon() {
    try {
        if (!Taskbar.isTaskbarSupported()) {
            System.err.println("Dock icon: Taskbar not supported")
            return
        }
        val taskbar = Taskbar.getTaskbar()
        if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
            System.err.println("Dock icon: ICON_IMAGE feature not supported")
            return
        }

        taskbar.iconImage = renderAppIcon()
        System.err.println("Dock icon: set successfully")
    } catch (error: UnsupportedOperationException) {
        System.err.println("Dock icon: failed - ${error.message}")
    } catch (error: SecurityException) {
        System.err.println("Dock icon: failed - ${error.message}")
    }
}

private fun renderAppIcon(): BufferedImage {
    val image = BufferedImage(DOCK_ICON_SIZE, DOCK_ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    val unit = DOCK_ICON_SIZE / ICON_GRID_UNITS

    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    graphics.drawIconBackground(unit)
    graphics.drawWindowDots(unit)
    graphics.drawBaseline(unit)
    graphics.drawBars(unit)
    graphics.drawChart(unit)
    graphics.dispose()

    return image
}

private fun Graphics2D.drawIconBackground(unit: Double) {
    val body = RoundRectangle2D.Double(
        ICON_MARGIN_UNITS * unit,
        ICON_MARGIN_UNITS * unit,
        ICON_BODY_SIZE_UNITS * unit,
        ICON_BODY_SIZE_UNITS * unit,
        ICON_CORNER_ARC_UNITS * unit,
        ICON_CORNER_ARC_UNITS * unit,
    )

    color = Color(BACKGROUND_RGB)
    fill(body)
    color = Color(BORDER_RGB)
    stroke = BasicStroke((BORDER_STROKE_WIDTH_UNITS * unit).toFloat())
    draw(body)
}

private fun Graphics2D.drawWindowDots(unit: Double) {
    windowDots.forEach { dot ->
        color = dot.color
        fill(circle(dot.centerXUnits, dot.centerYUnits, WINDOW_DOT_RADIUS_UNITS, unit))
    }
}

private fun Graphics2D.drawBaseline(unit: Double) {
    color = Color(BASELINE_ARGB, true)
    stroke = BasicStroke((BORDER_STROKE_WIDTH_UNITS * unit).toFloat())
    draw(
        Line2D.Double(
            BASELINE_START_X_UNITS * unit,
            BASELINE_Y_UNITS * unit,
            BASELINE_END_X_UNITS * unit,
            BASELINE_Y_UNITS * unit,
        ),
    )
}

private fun Graphics2D.drawBars(unit: Double) {
    color = Color(SUCCESS_RGB)
    bars.forEach { bar ->
        fill(
            Rectangle2D.Double(
                bar.xUnits * unit,
                bar.yUnits * unit,
                BAR_WIDTH_UNITS * unit,
                bar.heightUnits * unit,
            ),
        )
    }
}

private fun Graphics2D.drawChart(unit: Double) {
    color = Color(CHART_LINE_RGB)
    stroke = BasicStroke(
        (LINE_STROKE_WIDTH_UNITS * unit).toFloat(),
        BasicStroke.CAP_ROUND,
        BasicStroke.JOIN_ROUND,
    )
    draw(chartPath(unit))

    color = Color(DATA_POINT_RGB)
    chartPoints.forEach { point ->
        fill(circle(point.xUnits, point.yUnits, DATA_POINT_RADIUS_UNITS, unit))
    }
}

private fun chartPath(unit: Double): GeneralPath {
    val path = GeneralPath()
    val firstPoint = chartPoints.first()
    path.moveTo(firstPoint.xUnits * unit, firstPoint.yUnits * unit)
    chartPoints.drop(1).forEach { point ->
        path.lineTo(point.xUnits * unit, point.yUnits * unit)
    }
    return path
}

private fun circle(
    centerXUnits: Double,
    centerYUnits: Double,
    radiusUnits: Double,
    unit: Double,
) = Ellipse2D.Double(
    centerXUnits * unit - radiusUnits * unit,
    centerYUnits * unit - radiusUnits * unit,
    radiusUnits * LINE_STROKE_WIDTH_UNITS * unit,
    radiusUnits * LINE_STROKE_WIDTH_UNITS * unit,
)

private data class IconDot(
    val centerXUnits: Double,
    val centerYUnits: Double,
    val color: Color,
)

private data class IconBar(
    val xUnits: Double,
    val yUnits: Double,
    val heightUnits: Double,
)

private data class IconPoint(
    val xUnits: Double,
    val yUnits: Double,
)
