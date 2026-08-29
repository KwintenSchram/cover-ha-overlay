# Contributing to Cover HA Overlay

Thank you for your interest in contributing!

## Development Setup
1. **Prerequisites**:
   - Android Studio Ladybug or newer
   - JDK 21
   - Android SDK 34 (API 34)
   - Samsung Galaxy Z Flip or Fold physical device (or Android Emulator with secondary display configured)

2. **Clone & Build**:
   ```bash
   git clone https://github.com/KwintenSchram/cover-ha-overlay.git
   cd cover-ha-overlay
   ./gradlew assembleDebug
   ```

3. **Running Automated Tests**:
   ```bash
   ./gradlew testDebugUnitTest jacocoTestReport
   ```

4. **Pull Request Guidelines**:
   - Ensure all automated unit tests pass before opening a PR.
   - Maintain strict `WRAP_CONTENT` bounds on the overlay and preserve zero-activity background execution.
   - Follow Kotlin official style guidelines.

