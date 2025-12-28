package com.coroutines.androidresourceusagetracker

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon

object ResourceUsageIconGenerator {

    private const val ICON_SIZE = 16

    fun createIcon(usageCount: Int): Icon {
        val color = when {
            usageCount == 0 -> Color(128, 128, 128) // Grey
            usageCount == 1 -> Color(66, 133, 244)  // Blue
            else -> Color(234, 67, 53)              // Red
        }

        return createCircleIcon(usageCount.toString(), color)
    }

    fun createLoadingIcon(): Icon {
        return createCircleIcon("?", Color(200, 200, 200))
    }

    private fun createCircleIcon(text: String, backgroundColor: Color): Icon {
        val image = BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()

        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Draw circle
        g2d.color = backgroundColor
        g2d.fillOval(0, 0, ICON_SIZE, ICON_SIZE)

        // Draw text
        g2d.color = Color.WHITE
        g2d.font = Font("SansSerif", Font.BOLD, 10)
        val metrics = g2d.fontMetrics
        val x = (ICON_SIZE - metrics.stringWidth(text)) / 2
        val y = ((ICON_SIZE - metrics.height) / 2) + metrics.ascent
        g2d.drawString(text, x, y)

        g2d.dispose()
        return ImageIcon(image)
    }
}