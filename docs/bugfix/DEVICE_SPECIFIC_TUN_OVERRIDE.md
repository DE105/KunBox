# Device-Specific TUN Stack Override

## Issue
Certain devices, specifically the **Samsung Galaxy S20+ 5G (SM-G986U)** running Android 13, fail to support sing-box's `system` or `mixed` TUN stack modes.
Attempting to use these modes results in a `bind forwarder to interface: operation not permitted` error, causing connection failures.

Although the app has an auto-fallback mechanism, it previously required a failed start attempt and a restart to trigger.

## Solution
We have implemented a proactive override in `ConfigRepository.kt` and **completely removed the reactive fallback mechanism**.
When the device model matches `SM-G986U`, the app **forcedly uses `gVisor` stack mode** at the configuration generation level.

### Key Behavior
- **Effective Override**: The generated `config.json` passed to sing-box core will always use `stack: "gvisor"`.
- **Visual Comfort**: The UI settings will still reflect the user's choice (e.g., "System" or "Mixed"). This prevents confusion for users who might insist on selecting "System" mode, unaware of their device's kernel limitations.
- **Log Warning**: A warning is logged: `Device SM-G986U detected, forcing GVISOR stack (ignoring user selection: ...)`
- **No Restart Loop**: Since the config is correct from the start, no "start -> fail -> restart" loop occurs. The old fallback logic causing infinite loops has been deleted.

## Affected Devices
- Samsung SM-G986U

## Code Location
- `app/src/main/java/com/kunk/singbox/repository/ConfigRepository.kt` -> `getEffectiveTunStack()`
- `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt` (Fallback logic removed)
