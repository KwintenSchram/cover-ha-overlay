package com.haoverlay.coverscreen.data

import com.google.common.truth.Truth.assertThat
import com.haoverlay.coverscreen.data.model.DockPosition
import com.haoverlay.coverscreen.data.model.HaConfig
import com.haoverlay.coverscreen.data.model.IconSize
import com.haoverlay.coverscreen.data.model.OverlayButtonConfig
import com.haoverlay.coverscreen.data.model.OverlayOrientation
import com.haoverlay.coverscreen.data.model.OverlaySettings
import com.haoverlay.coverscreen.data.storage.ConfigTransfer
import com.haoverlay.coverscreen.data.storage.SecureConfigManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Before
import org.junit.Test

class ConfigTransferTest {

    private lateinit var manager: SecureConfigManager

    private var storedConfig = HaConfig(
        baseUrl = "https://home.example.test",
        accessToken = "EXISTING_TOKEN",
        useWebSocket = true,
        pollIntervalSeconds = 10
    )
    private var storedSettings = OverlaySettings(
        dockPosition = DockPosition.BOTTOM_LEFT,
        orientation = OverlayOrientation.VERTICAL,
        iconSize = IconSize.NORMAL,
        showButtonLabels = false
    )
    private var storedButtons = listOf(OverlayButtonConfig(entityId = "light.old", label = "Old"))

    @Before
    fun setup() {
        manager = mockk(relaxed = true)
        every { manager.getHaConfig() } answers { storedConfig }
        every { manager.getOverlaySettings() } answers { storedSettings }
        every { manager.getButtons() } answers { storedButtons }
    }

    // --- the footgun this class exists to prevent -------------------------------------------

    @Test
    fun `haConfig without accessToken preserves the stored token`() {
        val saved = slot<HaConfig>()
        every { manager.saveHaConfig(capture(saved)) } answers {}

        val result = ConfigTransfer.import(
            manager,
            """{"haConfig":{"baseUrl":"https://new.example.test","useWebSocket":true}}"""
        )

        assertThat(result).isInstanceOf(ConfigTransfer.Result.Success::class.java)
        assertThat(saved.captured.accessToken).isEqualTo("EXISTING_TOKEN")
        assertThat(saved.captured.baseUrl).isEqualTo("https://new.example.test")
        assertThat((result as ConfigTransfer.Result.Success).summary).contains("token preserved")
    }

    @Test
    fun `haConfig with accessToken replaces the stored token`() {
        val saved = slot<HaConfig>()
        every { manager.saveHaConfig(capture(saved)) } answers {}

        val result = ConfigTransfer.import(
            manager,
            """{"haConfig":{"accessToken":"NEW_TOKEN"}}"""
        )

        assertThat(saved.captured.accessToken).isEqualTo("NEW_TOKEN")
        assertThat((result as ConfigTransfer.Result.Success).summary).contains("token replaced")
    }

    // --- partial update semantics -----------------------------------------------------------

    @Test
    fun `settings patch leaves omitted fields untouched`() {
        val saved = slot<OverlaySettings>()
        every { manager.saveOverlaySettings(capture(saved)) } answers {}

        ConfigTransfer.import(manager, """{"settings":{"backgroundOpacity":0.5}}""")

        assertThat(saved.captured.backgroundOpacity).isEqualTo(0.5f)
        // Not mentioned in the patch, so it must survive rather than reset to the default.
        assertThat(saved.captured.orientation).isEqualTo(OverlayOrientation.VERTICAL)
        assertThat(saved.captured.dockPosition).isEqualTo(DockPosition.BOTTOM_LEFT)
        assertThat(saved.captured.showButtonLabels).isFalse()
    }

    // --- typo detection ----------------------------------------------------------------------

    @Test
    fun `unrecognised settings keys are reported rather than silently dropped`() {
        // These are the exact field names a hand-written payload got wrong in practice.
        val result = ConfigTransfer.import(
            manager,
            """{"settings":{"isVerticalOrientation":true,"buttonSizeDp":48,"accentColorHex":"#3B82F6"}}"""
        )

        val summary = (result as ConfigTransfer.Result.Success).summary
        assertThat(summary).contains("settings.isVerticalOrientation is not a recognised field")
        assertThat(summary).contains("settings.buttonSizeDp is not a recognised field")
        assertThat(summary).contains("settings.accentColorHex is not a recognised field")
    }

    @Test
    fun `unrecognised button keys are reported with their index`() {
        val result = ConfigTransfer.import(
            manager,
            """{"buttons":[{"entityId":"light.a","bogusField":1}]}"""
        )
        assertThat((result as ConfigTransfer.Result.Success).summary)
            .contains("buttons[0].bogusField is not a recognised field")
    }

    @Test
    fun `an unrecognised enum constant fails loudly instead of storing null`() {
        val result = ConfigTransfer.import(manager, """{"settings":{"dockPosition":"MIDDLE_OF_NOWHERE"}}""")
        assertThat(result).isInstanceOf(ConfigTransfer.Result.Failure::class.java)
        assertThat((result as ConfigTransfer.Result.Failure).message).contains("dockPosition")
    }

    // --- buttons -----------------------------------------------------------------------------

    @Test
    fun `buttons are replaced wholesale and every model field round-trips`() {
        val saved = slot<List<OverlayButtonConfig>>()
        every { manager.saveButtons(capture(saved)) } answers {}

        ConfigTransfer.import(
            manager,
            """
            {"buttons":[
              {"entityId":"switch.door_remote","domain":"switch","service":"toggle",
               "iconName":"door","label":"Building Door","customColorHex":"#3B82F6",
               "order":0,"showState":false,
               "guardSensorEntityId":"binary_sensor.hall","guardTriggerState":"on",
               "guardConfirmationWindowMs":10000},
              {"entityId":"lock.front","domain":"lock","service":"open","iconName":"lock_open",
               "label":"Open Latch","order":1,"showState":false,
               "requireConfirmationWhenLocked":true,"targetLockEntityId":"lock.front"}
            ]}
            """.trimIndent()
        )

        assertThat(saved.captured).hasSize(2)
        val first = saved.captured[0]
        assertThat(first.entityId).isEqualTo("switch.door_remote")
        // showState has no control in the UI, so the programmatic path is the only way to set it.
        assertThat(first.showState).isFalse()
        assertThat(first.guardSensorEntityId).isEqualTo("binary_sensor.hall")
        assertThat(first.guardConfirmationWindowMs).isEqualTo(10000L)

        val second = saved.captured[1]
        assertThat(second.service).isEqualTo("open")
        assertThat(second.requireConfirmationWhenLocked).isTrue()
        assertThat(second.targetLockEntityId).isEqualTo("lock.front")
    }

    @Test
    fun `a button with a blank entityId is flagged`() {
        val result = ConfigTransfer.import(manager, """{"buttons":[{"label":"nothing"}]}""")
        assertThat((result as ConfigTransfer.Result.Success).summary)
            .contains("buttons[0] has an empty entityId")
    }

    // --- export ------------------------------------------------------------------------------

    @Test
    fun `export omits the access token by default`() {
        val json = ConfigTransfer.export(manager, includeToken = false)
        assertThat(json).doesNotContain("EXISTING_TOKEN")
        assertThat(json).contains("https://home.example.test")
    }

    @Test
    fun `export includes the token only when explicitly requested`() {
        val json = ConfigTransfer.export(manager, includeToken = true)
        assertThat(json).contains("EXISTING_TOKEN")
    }

    @Test
    fun `an exported payload can be imported back unchanged`() {
        val saved = slot<OverlaySettings>()
        every { manager.saveOverlaySettings(capture(saved)) } answers {}

        val result = ConfigTransfer.import(manager, ConfigTransfer.export(manager))

        assertThat(result).isInstanceOf(ConfigTransfer.Result.Success::class.java)
        assertThat(saved.captured).isEqualTo(storedSettings)
        // The advisory "_"-prefixed keys in an export must not be reported as unknown fields.
        assertThat((result as ConfigTransfer.Result.Success).summary).doesNotContain("not a recognised field")
    }

    // --- misc --------------------------------------------------------------------------------

    @Test
    fun `an empty payload fails rather than reporting a no-op success`() {
        val result = ConfigTransfer.import(manager, """{}""")
        assertThat(result).isInstanceOf(ConfigTransfer.Result.Failure::class.java)
    }

    @Test
    fun `malformed json fails cleanly`() {
        val result = ConfigTransfer.import(manager, "not json at all")
        assertThat(result).isInstanceOf(ConfigTransfer.Result.Failure::class.java)
    }

    @Test
    fun `schema names every accepted enum constant`() {
        val schema = ConfigTransfer.schema()
        assertThat(schema).contains("BOTTOM_LEFT")
        assertThat(schema).contains("VERTICAL")
        assertThat(schema).contains("AUTO_DETECT_COVER")
        assertThat(schema).contains("showState")
    }
}
