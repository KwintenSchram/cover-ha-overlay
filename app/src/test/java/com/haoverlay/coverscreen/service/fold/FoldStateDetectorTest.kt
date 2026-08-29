package com.haoverlay.coverscreen.service.fold

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import com.google.common.truth.Truth.assertThat
import com.haoverlay.coverscreen.service.display.CoverDisplayManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoldStateDetectorTest {

    private lateinit var context: Context
    private lateinit var displayManager: DisplayManager
    private lateinit var powerManager: PowerManager
    private lateinit var coverDisplayManager: CoverDisplayManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var foldStateDetector: FoldStateDetector

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        displayManager = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)
        coverDisplayManager = mockk(relaxed = true)

        every { context.getSystemService(Context.DISPLAY_SERVICE) } returns displayManager
        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { powerManager.isInteractive } returns true

        foldStateDetector = FoldStateDetector(context, coverDisplayManager, testScope)
    }

    @Test
    fun `when cover display is ON and default is OFF, state is FOLDED and cover is active`() {
        val coverDisplay = mockk<Display>(relaxed = true)
        every { coverDisplay.state } returns Display.STATE_ON
        every { coverDisplayManager.findCoverDisplay() } returns coverDisplay

        val defaultDisplay = mockk<Display>(relaxed = true)
        every { defaultDisplay.state } returns Display.STATE_OFF
        every { displayManager.getDisplay(Display.DEFAULT_DISPLAY) } returns defaultDisplay

        foldStateDetector.evaluateCurrentState()

        assertThat(foldStateDetector.isCoverDisplayActive.value).isTrue()
        assertThat(foldStateDetector.foldState.value).isEqualTo(DeviceFoldState.FOLDED)
    }

    @Test
    fun `when default display is ON, state is UNFOLDED`() {
        val coverDisplay = mockk<Display>(relaxed = true)
        every { coverDisplay.state } returns Display.STATE_OFF
        every { coverDisplayManager.findCoverDisplay() } returns coverDisplay

        val defaultDisplay = mockk<Display>(relaxed = true)
        every { defaultDisplay.state } returns Display.STATE_ON
        every { displayManager.getDisplay(Display.DEFAULT_DISPLAY) } returns defaultDisplay

        foldStateDetector.evaluateCurrentState()

        assertThat(foldStateDetector.isCoverDisplayActive.value).isFalse()
        assertThat(foldStateDetector.foldState.value).isEqualTo(DeviceFoldState.UNFOLDED)
    }
}
