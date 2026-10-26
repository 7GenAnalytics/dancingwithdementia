package com.seven7genanalytics.dancingwithdementia;

import android.app.admin.DeviceAdminReceiver;

/**
 * Marker device-admin receiver. When this app is provisioned as the
 * tablet's Device Owner (via "adb shell dpm set-device-owner ..." on a
 * freshly factory-reset device), MainActivity is allowed to lock the
 * screen to itself (Lock Task Mode) and disable the status bar / keyguard
 * without needing the user to manually pin the app each time.
 *
 * No special handling is required here beyond declaring the receiver -
 * DevicePolicyManager checks isDeviceOwnerApp() against the package, not
 * against anything this class does at runtime.
 */
public class MusicKioskAdminReceiver extends DeviceAdminReceiver {
}
