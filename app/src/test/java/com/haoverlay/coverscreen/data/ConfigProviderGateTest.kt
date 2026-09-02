package com.haoverlay.coverscreen.data

import com.google.common.truth.Truth.assertThat
import com.haoverlay.coverscreen.data.provider.ConfigProvider
import org.junit.Test

/**
 * [ConfigProvider.checkAccess] is the entire authorisation boundary for the configuration channel.
 * The provider is exported so that adb can reach it at all, so this check is the only thing standing
 * between an installed app and the Home Assistant credentials.
 *
 * The check is deliberately a pure function of the calling uid so it can be pinned here rather than
 * only exercised through a real Binder.
 */
class ConfigProviderGateTest {

    private companion object {
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        const val SYSTEM_UID = 1000
        const val FIRST_APP_UID = 10000
    }

    @Test
    fun `adb shell is allowed`() {
        assertThat(ConfigProvider.checkAccess(SHELL_UID))
            .isInstanceOf(ConfigProvider.Access.Allowed::class.java)
    }

    @Test
    fun `root is allowed`() {
        assertThat(ConfigProvider.checkAccess(ROOT_UID))
            .isInstanceOf(ConfigProvider.Access.Allowed::class.java)
    }

    @Test
    fun `ordinary app uids are denied`() {
        for (uid in listOf(FIRST_APP_UID, 10123, 10999, 12345)) {
            val access = ConfigProvider.checkAccess(uid)
            assertThat(access).isInstanceOf(ConfigProvider.Access.Denied::class.java)
            assertThat((access as ConfigProvider.Access.Denied).message).contains("Refused")
            assertThat(access.message).contains(uid.toString())
        }
    }

    @Test
    fun `even the system uid is denied`() {
        // Nothing about this provider needs to be reachable by system_server, and widening the gate
        // to "privileged" rather than "shell or root" would let far more code in.
        assertThat(ConfigProvider.checkAccess(SYSTEM_UID))
            .isInstanceOf(ConfigProvider.Access.Denied::class.java)
    }

    @Test
    fun `the app's own uid is denied, so an in-process caller cannot self-authorise`() {
        // getCallingUid() returns the process's own uid for in-process calls, which must not pass.
        assertThat(ConfigProvider.checkAccess(10327))
            .isInstanceOf(ConfigProvider.Access.Denied::class.java)
    }

    @Test
    fun `the denial message names the caller but never echoes configuration`() {
        val access = ConfigProvider.checkAccess(10123) as ConfigProvider.Access.Denied
        assertThat(access.message).contains("10123")
        assertThat(access.message).doesNotContain("accessToken")
        assertThat(access.message).doesNotContain("baseUrl")
    }
}
