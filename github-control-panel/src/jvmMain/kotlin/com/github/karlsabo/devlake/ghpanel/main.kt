package com.github.karlsabo.devlake.ghpanel

import androidx.compose.ui.window.application
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

fun main() {
    System.setProperty("apple.awt.application.name", "Git Control Panel")
    setDockIcon()
    application {
        GitHubControlPanelApp(onExitApplication = ::exitApplication)
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

        taskbar.iconImage = renderAppIcon(256)
        System.err.println("Dock icon: set successfully")
    } catch (e: Exception) {
        System.err.println("Dock icon: failed - ${e.message}")
        e.printStackTrace()
    }
}

private fun renderAppIcon(size: Int): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

    val s = size / 32.0

    // Background
    g.color = Color(0x1E, 0x23, 0x2D)
    g.fill(RoundRectangle2D.Double(1 * s, 1 * s, 30 * s, 30 * s, 12 * s, 12 * s))
    g.color = Color(0x46, 0x50, 0x5F)
    g.stroke = BasicStroke(s.toFloat())
    g.draw(RoundRectangle2D.Double(1 * s, 1 * s, 30 * s, 30 * s, 12 * s, 12 * s))

    // Window dots
    g.color = Color(0xFF, 0x5F, 0x55)
    g.fill(Ellipse2D.Double((7 - 1.5) * s, (7 - 1.5) * s, 3 * s, 3 * s))
    g.color = Color(0xFF, 0xBE, 0x3C)
    g.fill(Ellipse2D.Double((11 - 1.5) * s, (7 - 1.5) * s, 3 * s, 3 * s))
    g.color = Color(0x3C, 0xC8, 0x78)
    g.fill(Ellipse2D.Double((15 - 1.5) * s, (7 - 1.5) * s, 3 * s, 3 * s))

    // Baseline
    g.color = Color(255, 255, 255, 46)
    g.stroke = BasicStroke(s.toFloat())
    g.draw(Line2D.Double(6 * s, 22 * s, 26 * s, 22 * s))

    // Bars
    g.color = Color(0x3C, 0xC8, 0x78)
    g.fill(Rectangle2D.Double(9 * s, 22 * s, 2 * s, 4 * s))
    g.fill(Rectangle2D.Double(13 * s, 21 * s, 2 * s, 5 * s))
    g.fill(Rectangle2D.Double(17 * s, 20 * s, 2 * s, 6 * s))
    g.fill(Rectangle2D.Double(21 * s, 19 * s, 2 * s, 7 * s))

    // Line chart
    g.color = Color(0x50, 0xA0, 0xFF)
    g.stroke = BasicStroke((2 * s).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val path = GeneralPath()
    path.moveTo(7 * s, 20 * s)
    path.lineTo(12 * s, 17 * s)
    path.lineTo(17 * s, 18 * s)
    path.lineTo(22 * s, 14 * s)
    path.lineTo(25 * s, 12 * s)
    g.draw(path)

    // Data points
    g.color = Color(0xF5, 0xF7, 0xFA)
    for ((px, py) in listOf(7 to 20, 12 to 17, 17 to 18, 22 to 14, 25 to 12)) {
        val r = 1.4 * s
        g.fill(Ellipse2D.Double(px * s - r, py * s - r, r * 2, r * 2))
    }

    g.dispose()
    return img
}
