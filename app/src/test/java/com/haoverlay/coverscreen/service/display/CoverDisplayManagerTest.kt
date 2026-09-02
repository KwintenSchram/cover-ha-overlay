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

    private fun display(
        id: Int,
        name: String,
        width: Int = 0,
        height: Int = 0,
        flags: Int = 0
    ): Display = mockk<Display>(relaxed = true).also { d ->
        every { d.displayId } returns id
        every { d.name } returns name
        every { d.flags } returns flags
        every { d.getRealSize(any()) } answers {
            val point = firstArg<Point>()
            point.x = width
            point.y = height
        }
    }

    // --- accepted -------------------------------------------------------------------------

    @Test
    fun `display with sub or cover in name is accepted`() {
        val (isCover, reason) = coverDisplayManager.evaluateIfCoverDisplay(
            display(1, "Samsung Sub Display"), 2
        )
        assertThat(isCover).isTrue()
        assertThat(reason).contains("cover display keyword")
    }

    @Test
    fun `flip7 outer display resolution 948x1048 is accepted by resolution heuristic`() {
        val (isCover, reason) = coverDisplayManager.evaluateIfCoverDisplay(
            display(1, "Ingebouwd scherm", width = 948, height = 1048, flags = Display.FLAG_PRESENTATION), 2
        )
        assertThat(isCover).isTrue()
        assertThat(reason).contains("Z Flip sub-display")
    }

    @Test
    fun `flip5 outer display resolution 720x748 is accepted`() {
        val (isCover, _) = coverDisplayManager.evaluateIfCoverDisplay(
            display(1, "Built-in Screen", width = 720, height = 748), 2
        )
        assertThat(isCover).isTrue()
    }

    @Test
    fun `flip4 outer display resolution 260x512 is accepted`() {
        val (isCover, _) = coverDisplayManager.evaluateIfCoverDisplay(
            display(1, "Built-in Screen", width = 260, height = 512), 2
        )
        assertThat(isCover).isTrue()
    }

    // --- rejected -------------------------------------------------------------------------

    @Test
    fun `default display is rejected as cover display`() {
        val (isCover, _) = coverDisplayManager.evaluateIfCoverDisplay(
            display(Display.DEFAULT_DISPLAY, "Main Screen"), 2
        )
        assertThat(isCover).isFalse()
    }

    @Test
    fun `chromecast session is never treated as the cover display`() {
        val (isCover, reason) = coverDisplayManager.evaluateIfCoverDisplay(
            display(2, "Chromecast-1234", width = 1920, height = 1080, flags = Display.FLAG_PRESENTATION), 2
        )
        assertThat(isCover).isFalse()
        assertThat(reason).contains("external-display keyword")
    }

    @Test
    fun `display literally named external is rejected, not accepted`() {
        // Regression: "external" used to sit in the *accept* keyword list.
        val (isCover, _) = coverDisplayManager.evaluateIfCoverDisplay(
            display(2, "External Display", width = 900, height = 900), 2
        )
        assertThat(isCover).isFalse()
    }

    @Test
    fun `hdmi output is rejected even at a square-ish resolution`() {
        val (isCover, _) = coverDisplayManager.evaluateIfCoverDisplay(
            display(3, "HDMI Screen", width = 1024, height = 768), 2
        )
        assertThat(isCover).isFalse()
    }

    @Test
    fun `simulated developer-options display with FLAG_PRIVATE is rejected`() {
        val (isCover, reason) = coverDisplayManager.evaluateIfCoverDisplay(
            display(4, "Built-in Screen 2", width = 1000, height = 1000, flags = Display.FLAG_PRIVATE), 2
        )
        assertThat(isCover).isFalse()
        assertThat(reason).contains("FLAG_PRIVATE")
    }

    @Test
    fun `FLAG_PRESENTATION alone no longer qualifies a display as the cover screen`() {
        // Regression: FLAG_PRESENTATION is exactly what external displays carry, yet it used
        // to be sufficient on its own.
        val (isCover, _) = coverDisplayManager.evaluateIfCoverDisplay(
            display(5, "Sony TV", width = 3840, height = 2160, flags = Display.FLAG_PRESENTATION), 2
        )
        assertThat(isCover).isFalse()
    }

    @Test
    fun `unknown secondary display is not claimed by a catch-all`() {
        // Regression: the old final rule was "if there are 2+ displays, this one is the cover".
        val (isCover, reason) = coverDisplayManager.evaluateIfCoverDisplay(
            display(6, "Unknown Panel", width = 1600, height = 2560), 2
        )
        assertThat(isCover).isFalse()
        assertThat(reason).contains("No cover-display signature")
    }
}
