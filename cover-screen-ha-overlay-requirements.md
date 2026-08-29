# Requirement Document: Cover Screen Home Assistant Quick-Control Overlay

## Target device
Samsung Galaxy Z Flip7. Cover display resolution: 948×1048 physical px (~1422×1572dp @3.0 density). Android/One UI current release.

## Objective
Native Android application that renders a small, always-available overlay of icon-buttons on the cover screen (Flex Window / cover display), each bound to a Home Assistant entity/service call. Tapping an icon executes the HA action directly, with no Activity launch and no navigation away from whatever the user is currently viewing on the cover screen.

## Explicit non-goals
- Do NOT build a full-screen takeover of the cover display.
- Do NOT replace or intercept Samsung's native clock/notification/widget UI.
- Do NOT require the user to swipe to a separate page to reach controls.
- Do NOT open any Activity or app UI on tap — actions must fire from background/service context only.

## Functional requirements

1. **Overlay window**
   - `WindowManager.LayoutParams` type `TYPE_APPLICATION_OVERLAY`.
   - Sized to icon-cluster bounds only (e.g. a row/column of 3–6 icons), not full screen.
   - Transparent background outside icon bounds.
   - Touch-passthrough for all non-icon area (`FLAG_NOT_TOUCHABLE` scoped correctly, or use a touch region approach so the rest of the display remains interactive underneath).
   - Position configurable (default: fixed corner or edge dock), persisted across restarts.

2. **Display targeting**
   - Overlay must render on the cover display specifically, identified at runtime via `DisplayManager.getDisplays()`.
   - Must correctly distinguish cover display vs. main display vs. any other display Samsung exposes (this varies by One UI version — do not hardcode a display index; identify by display characteristics/name/flags at runtime, with a logged fallback if detection fails).
   - Must re-attach overlay correctly on fold/unfold transitions (overlay should only be visible when device is folded/cover display is active).

3. **Fold-state detection**
   - Use Android's `FoldingFeature`/`WindowLayoutInfo` (Jetpack WindowManager library) or Samsung's fold-state broadcast if the Jetpack API proves insufficient on this device — verify which is reliable on Z Flip7 during implementation and document the choice.
   - Overlay attaches when device enters folded state and cover display is active; detaches (or at minimum stops consuming touch) when unfolded.

4. **Icon actions**
   - Each icon maps to one Home Assistant entity + one service call (e.g. `light.toggle`, `switch.toggle`, `scene.turn_on`).
   - Tapping an icon fires the HA REST API call (`POST /api/services/<domain>/<service>`) using a stored long-lived access token, executed from a background context (WorkManager one-off job or a bound service call) — no Activity, no visible transition.
   - Provide brief non-blocking visual feedback on tap (e.g. icon flash/color change) sourced from the HA response, without opening any window beyond the existing overlay.
   - Icon set is user-configurable (entity ID, service, icon image/label) via a normal settings Activity — this settings screen is the only permitted full-UI Activity in the app, used for setup only, not for routine use.

5. **State sync (optional, specify if in scope)**
   - If icon should reflect current HA state (e.g. light on/off), poll the entity via HA's REST API or subscribe via WebSocket, and update icon appearance accordingly.
   - Define polling interval or WebSocket-keepalive strategy; must not materially harm battery life given the service runs continuously.

6. **Foreground service**
   - A foreground service owns the overlay's lifecycle (create/attach/detach/destroy) and any polling/WebSocket connection.
   - Must show a persistent low-priority notification per Android foreground service requirements.
   - Must survive Samsung's aggressive background process management — document in the implementation notes what battery-optimization exemptions the user needs to grant manually (this cannot be done programmatically), and instruct the app to detect and prompt for these on first run.

## Permissions required
- `SYSTEM_ALERT_WINDOW` (draw over other apps) — user must grant manually in Settings; app must detect if not granted and route user to the grant screen.
- `FOREGROUND_SERVICE` (and relevant sub-type, e.g. `FOREGROUND_SERVICE_SPECIAL_USE` or whichever applies under current target SDK rules).
- Internet permission for HA API calls.
- Battery optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) — request via user-facing prompt, not silently.

## Configuration data
- HA base URL, long-lived access token, entity/service mappings, icon assets, overlay position — store in local encrypted preferences (`EncryptedSharedPreferences`), not plaintext, since the token grants HA control.

## Edge cases to handle
- HA server unreachable: icon should indicate failure state distinctly from "off" state, not fail silently.
- Overlay surviving device reboot: service must restart on boot (`BOOT_COMPLETED` receiver) and re-attach overlay in the correct fold state.
- One UI killing the foreground service despite exemption: implement a watchdog (e.g. `AlarmManager`-triggered health check) that restarts the service if it's found dead.
- Multiple rapid taps: debounce to avoid duplicate service calls.
- Cover display not detected at all (e.g. running on non-foldable during dev/testing): fail gracefully with a clear log/error rather than crashing.

## Testing / acceptance criteria
- Icons visible on cover screen immediately when device is folded and screen wakes, with no visible delay or flash of a different UI.
- Tapping an icon changes the corresponding HA entity state with no Activity/app window opening at any point.
- Rest of the cover screen (clock, notifications, native widgets) remains fully swipeable and interactive around the overlay.
- Overlay and service survive: screen off/on cycle, fold/unfold cycle, device reboot, 24hr idle (battery-optimization survival check).
- Settings Activity allows adding/editing/removing icon-to-entity mappings without rebuilding the app.

## Deliverables expected from implementation
- Android Studio project (Kotlin), buildable to a debug APK for sideloading via ADB — no Play Store distribution needed.
- README covering: how to grant required permissions manually, how to obtain and enter an HA long-lived access token, how to sideload, and known One UI version(s) this was tested against.
