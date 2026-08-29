# Reddit Post: r/homeassistant

**Title:** I built an open-source native cover-screen quick controller for the Galaxy Z Flip (zero load time, works while locked!)

**Post Content:**

Hey r/homeassistant!

One of my biggest pet peeves with my Galaxy Z Flip was having to unlock the phone and wait for the full HA Android app/WebView to load through Good Lock just to turn off my living room lights or open the building gate.

I built a native, lightweight open-source Android app: **Cover HA Overlay**.

### Highlights:
- **Instant Taps**: Fired in ~100ms in the background. Zero activity launches or screen interruptions.
- **Works Over Lock Screen**: Accessible the millisecond you wake the cover screen (`FLAG_SHOW_WHEN_LOCKED`).
- **Touch Passthrough**: Your Samsung clock, notifications, and native widgets work completely untouched around the buttons.
- **Custom Safety Guards**: Supports conditional logic before firing (e.g. check a motion/light sensor before releasing a door, or require a confirmation double-tap if a lock is locked).
- **Battery-Friendly**: Automatically pauses WebSockets whenever the outer screen turns off.

The app is completely open source under Apache 2.0 and has zero analytics/trackers.

**GitHub & APK**: https://github.com/your-username/cover-ha-overlay
(F-Droid package is in review)

Let me know what you think or if you'd like to see specific entity types supported!
