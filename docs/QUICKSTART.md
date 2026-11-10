# XShifter Open — quick start

Android and macOS test releases · [Downloads](https://github.com/alexeinz/xshifter_open/releases) · [Screenshots](https://github.com/alexeinz/xshifter_open/blob/main/docs/SCREENSHOTS.md)

## 1. Install

**Android (8.0+):** download `XShifter-Open-<version>.apk` from this repository's Releases page and open it. Android may ask you to allow installation from the browser or file manager you used. Permission wording varies by device. See [Android's installation guidance](https://developer.android.com/distribute/marketing-tools/alternative-distribution).

If you installed one of the earlier local **debug APKs**, first export your profiles. The public release uses a different signing certificate, so Android will require you to uninstall the debug app before installing the release. Uninstalling removes the app's local profiles, identity and backups. Import your exported profiles afterwards. Later public releases use the same private release key.

**Mac (macOS 13+, Intel or Apple Silicon):** download and open `XShifter-Open-<version>-macOS-universal.dmg`, then drag **XShifter Open.app** to **Applications**. Alternatively, unzip the app ZIP and move the app to Applications. The app is ad-hoc signed, not Apple-notarized. If macOS blocks it, review [Apple's guidance for opening apps safely](https://support.apple.com/en-us/102445); any exception is your choice. Do not disable Gatekeeper globally.

`SHA256SUMS` on each release lists package hashes. On a Mac, run `shasum -a 256 <downloaded-file>` to compare a download with the published value.

## 2. Explore without a bike

On Android, tap **Try the demo**. On Mac, choose **Explore demo**. Gear positions, battery readings and changes are simulated; no motor commands are sent. Exit Demo mode before connecting real hardware.

## 3. Connect and preserve the current setup

1. Turn on Bluetooth and wake the E-Link. Close the original XShifter app and other phone/Mac connections to it.
2. Choose **Find my XShifter** on Android or **Find XShifter** on Mac. Allow the requested Bluetooth permissions; older Android versions also request location permission for scanning. Select the discovered device.
3. Wait for login and a complete gear-table read. Use **Read device state** (Android) or **Read device** (Mac) if needed. No complete reply means the displayed setup is unverified.
4. Export a copy before changing anything. Android: **Device → Export last device backup…**. Mac: **Use device table → Export profile…**. Import a saved profile later to review or restore it.

Keep the bike on a work stand. Keep the wheel/drivetrain free to turn and turn the cranks gently as appropriate while shifting. Keep fingers, clothing and tools clear. These builds still need live calibration testing on your device; do not start with an unverified replacement table.

## 4. Tune one gear

Enable **Bench mode** for the current connection. Select a gear and shift to it before adjusting it.

- **Android:** open **Tune**, select the gear, tap **Shift to selected gear**, then use the **0.1 mm** fine adjustments. Larger **0.5 mm** adjustments are available when needed. Finish with **Save tuning to XShifter** and check the subsequent readback.
- **Mac:** select a row, choose **Shift here**, select a trim step, and use **− Trim / + Trim**. Finish with **Save device tuning…** and review the confirmation and readback.

Only trim the gear the device reports as selected. Wait for each operation/readback to finish. Stale state triggers a fresh read before further changes. A matching readback verifies the current table; persistence after power cycling still needs checking. Recheck all gears on the stand before riding.

## 5. Exchange or apply profiles

Both apps use the same JSON profile format and integer cable units. Transfer an exported JSON file using your preferred file-transfer method, then import it in the other app. Profiles do not sync automatically.

On Android, use **Profiles → Import profile… / Export profile…**. On Mac, use **Import Android profile… / Export profile…**. The Mac table shows live device positions beside editable draft positions and their differences in millimetres.

Applying a profile replaces the device table and commits it. Review every position, mark the profile as measured/calibrated, and enable Bench mode. Use **Apply calibrated table to XShifter…** (Android) or **Apply draft to device…** (Mac). Keep the app open and the Mac awake during transfer, then check readback. A backup is saved before changes. Mac **Show calibration backups** reveals the latest saved backup; import a backup JSON to prepare a restore.

Live table upload supports **3–17 gears**. A SRAM/Shimano model or tooth list does not supply calibrated XShifter cable positions. Linear drafts are starting estimates only. Check the mechanical drivetrain's limits separately.

## 6. Battery and device information

Both apps show E-Link voltage and a **~percentage estimate** derived from the original app's voltage thresholds. Unknown, stale and Demo data are labelled. No capacity or battery-health measurement is available.

Android **Device → Battery notifications** opts in for the current connection. Allow notification permission when requested. Low warning defaults to ~20% (adjustable); critical warning is ~10%. Monitoring can continue while the phone is locked if Bluetooth stays connected. Disconnecting stops alerts. **Send test notification** verifies delivery without a live warning.

Android's session trend needs at least ten minutes of continuous readings. A sustained voltage drop may flag possible fast drain; motor load, temperature and charge also affect voltage. It is not a health diagnosis or remaining-runtime forecast. Demo/stale data never trigger live battery warnings. These notification/trend features are not included in the initial Mac app.

Use **Read firmware version** to request the E-Link version. Legacy replies are supported, but **No supported reply** means the app could not identify the returned version. Firmware flashing/dumping and bike-computer broadcasting are not implemented.

## If something does not work

- **No device:** wake it, check Bluetooth permission, and close competing apps. The original unit may allow only one active connection.
- **No complete table / timeout:** reconnect and read again. Do not apply another table to hide an unverified result.
- **Readback differs:** stop calibration and compare against your saved backup before continuing.
- **Version unavailable:** retry the separate version read and inspect the session log. No version is inferred from an app or cassette model.
- **No Android alert:** check the in-app monitoring switch, Android notification settings, and connection state. Try the test notification.

When reporting an issue, include the app version, platform, whether Demo mode reproduces it, and the relevant session-log messages. Remove device identifiers or other details you do not want to publish.
