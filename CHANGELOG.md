# Changelog

All notable changes to **Cover HA Overlay** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.2.0] - 2026-09-02

### Added
- **Configuration as data over `adb`.** A new `ConfigProvider` exposes `export`, `import` and
  `schema` methods, so a device can be provisioned, backed up and restored as JSON without
  tapping through the UI. It is also the only way to set fields the setup screen does not
  expose — `showState`, `guardTriggerState` and `guardConfirmationWindowMs` have no controls.
  - Restricted to the adb shell and root UIDs. The provider is `exported` so `adb` can reach it,
    but reachability is not authorisation: any other caller is refused by UID, whatever
    permissions it holds.
  - **Import is a partial update.** Keys absent from the payload keep their current value, so an
    `haConfig` block without `accessToken` no longer blanks the stored token — a footgun that
    cost a real configuration during 1.1.0 testing.
  - **Unrecognised field names are reported,** not silently dropped. A payload written against the
    wrong names used to appear to succeed while changing nothing.
  - **Exports omit the access token** unless explicitly requested, so a config file can be shared
    or committed without leaking a credential.
  - Payloads are passed base64-encoded, because `content call --extra key:type:value` splits its
    argument on colons and every JSON document is full of them.

### Context

1.1.0 closed a configuration backdoor — the app read `/data/local/tmp/restore_config.json` on
every launch and accepted credentials from intent extras on an exported activity. That hole was
also, in practice, the only way to configure the app programmatically or to back it up. Closing it
without a replacement left no supported path at all, and app data is deliberately excluded from
cloud backup and device transfer, so an uninstall meant total loss. This release supplies the
replacement, with the authentication the original never had.

## [1.1.0] - 2026-09-02

Security and correctness release. **Upgrading from 1.0.0 requires an uninstall** — 1.0.0 was
signed with the Android debug key and 1.1.0 is signed with a real release key.

### Security
- **Removed an unauthenticated configuration-restore path.** `MainActivity` ran its restore hook
  on *every* intent, including the plain launcher intent, and unconditionally read
  `/data/local/tmp/restore_config.json`. Combined with the activity being exported, any installed
  app could replace the Home Assistant server, access token and button set with a few string
  extras, and anything able to write that path had a persistent foothold. The hook is now
  debug-build-only, requires an explicit `ACTION_RESTORE_CONFIG`, and accepts only inline extras.
- **Release builds are signed with a real release key.** Previously `signingConfig` pointed at the
  SDK debug key, whose password is public, so anyone could ship an APK that installed as an
  in-place upgrade over a real install and inherited its overlay permission and data.
- **Excluded the plaintext fallback preference file from backup.** Only the encrypted store was
  excluded, so on a device where Keystore init failed the token was cloud-backed-up in the clear.
- **Fold-state receiver is no longer exported.** Any app could previously spoof
  `com.samsung.intent.action.FOLD_STATE` to force the overlay to attach.
- **Cleartext warning.** The setup screen now warns when an `http://` URL points at a public host.
- **Encrypted-storage fallback is now visible** instead of being a silent downgrade.
- Enabled R8 minification and resource shrinking for release builds.

### Fixed
- **Cover-screen detection no longer claims arbitrary secondary displays.** The heuristic ended
  with "any second display is the cover screen" and separately accepted anything with
  `FLAG_PRESENTATION` — the flag external displays carry. Casting to a TV or plugging into DeX
  could move door-unlock buttons onto that screen. External, remote and virtual displays are now
  rejected explicitly, and `"external"` has been moved from the accept list to the reject list.
- **WebSocket no longer reconnects forever on a rejected token.** `auth_invalid` used to trigger a
  fixed 5-second reconnect loop with no ceiling. Reconnects now use exponential backoff with
  jitter (2 s → 5 min) and stop entirely once the token is rejected, until the config changes.
- **Fixed a source-encoding bug** that rendered the Displays tab resolution as `948�1048`.
  `DisplayInfo.kt` was CP-1252 encoded, so the compiler substituted U+FFFD for `×`.
- **Permission cards now refresh on resume** instead of reporting "not granted" until a restart.
- **Display-cutout mode is guarded at API 30** rather than API 28, where the constant is not a
  defined mode.
- State polling issues one `GET /api/states` per interval instead of one request per entity.
- Removed a hardcoded `getDisplay(1)` probe from display enumeration.

### Changed
- `versionCode` 2 / `versionName` 1.1.0 — 1.0.0 shipped with a hardcoded `versionCode` of 1, so
  tagged releases could not install over one another.
- Removed two duplicated test classes; test suite is now 34 unique tests.
- Stripped UTF-8 BOMs from 12 source and documentation files.
- Documentation corrected where it overstated behaviour: the "0.0% idle CPU" and "zero wakelocks"
  claims are inaccurate given the 15-minute watchdog wakeup, which is now described honestly.

## [1.0.0] - 2026-08-29

### Initial Release
- **Samsung Galaxy Z Flip Support**: Multi-heuristic cover screen targeting for Flip7 (948 × 1048), Flip5/Flip6 (720 × 748), and Flip3/Flip4 (260 × 512).
- **Locked Screen Quick Actions**: Control smart home devices directly on the locked cover screen via `FLAG_SHOW_WHEN_LOCKED` without device unlock.
- **True Touch Passthrough**: Floating overlay strictly constrained to button cluster bounding box (`WRAP_CONTENT`), preserving full touch interaction with native clock and widgets.
- **Instant Background Dispatch**: Zero Activity popups; background REST/WebSocket service dispatch in ~100ms.
- **Safety Guards & Confirmation Windows**:
  - Binary sensor pre-check (e.g. hallway optical sensor) with double warning vibration and 10s override window.
  - Smart lock confirmation guard (double-tap confirmation before unlatching locked doors).
- **Zero Idle Power Drain**: Automatic pause of WebSockets and polling coroutines when the cover screen sleeps.
- **End-to-End Encryption**: Android Keystore AES-256-GCM encrypted local storage for access tokens and server URLs.
- **Material 3 Setup UI**: Entity browser, domain-smart service picker, custom color palettes, and 9-point docking layout selector.
- **One UI Resilience**: Foreground service with boot auto-restart and watchdog heartbeat.
