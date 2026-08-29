package com.haoverlay.coverscreen.service.display

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

@Suppress("DEPRECATION")
class CoverDisplayManagerTest {

    private lateinit var context: Context
    private lateinit var displayManager: DisplayManager
    private lateinit var coverDisplayManager: CoverDisplayManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        displayManager = mockk(relaxed = true)
        every { context.getSystemService(Context.DISPLAY_SERVICE) } returns displayManager

        coverDisplayManager = CoverDisplayManager(context)
    }

    @Test
    fun `default display is rejected as cover display`() {
        val defaultDisplay = mockk<Display>(relaxed = true)
        every { defaultDisplay.displayId } returns Display.DEFAULT_DISPLAY
        every { defaultDisplay.name } returns "Main Screen"

        val (isCover, _) = coverDisplayManager.evaluateIfCoverDisplay(defaultDisplay, 2)
        assertThat(isCover).isFalse()
    }

    @Test
    fun `display with sub or cover in name is accepted`() {
        val subDisplay = mockk<Display>(relaxed = true)
        every { subDisplay.displayId } returns 1
        every { subDisplay.name } returns "Samsung Sub Display"

        val (isCover, reason) = coverDisplayManager.evaluateIfCoverDisplay(subDisplay, 2)
        assertThat(isCover).isTrue()
        assertThat(reason).contains("cover display keyword")
    }

    @Test
    fun `flip7 outer display resolution 948x1048 is accepted by aspect ratio heuristic`() {
        val flip7Display = mockk<Display>(relaxed = true)
        every { flip7Display.displayId } returns 1
        every { flip7Display.name } returns "Ingebouwd scherm"
        every { flip7Display.flags } returns Display.FLAG_PRESENTATION
        
        every { flip7Display.getRealSize(any()) } answers {
            val point = firstArg<Point>()
            point.x = 948
            point.y = 1048
        }

        val (isCover, reason) = coverDisplayManager.evaluateIfCoverDisplay(flip7Display, 2)
        assertThat(isCover).isTrue()
        assertThat(reason).contains("match Z Flip sub-display")
    }
}
