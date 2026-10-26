package com.seven7genanalytics.dancingwithdementia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

/**
 * Recovers automatically after a power cut. A tablet that's had its power
 * disconnected (or the battery has gone flat) restarts with nothing
 * running - not this app, not Spotify - until something launches them.
 * This app already comes back on its own (it's the tablet's home screen,
 * so Android starts MainActivity as part of showing the home screen after
 * boot), but nothing previously told Spotify to start too, so it never
 * re-registered itself as a Connect device until a family member
 * happened to open it by hand - see the "device isn't showing" symptom in
 * spec.md's history.
 *
 * Two delayed steps, both fire-and-forget:
 *  1. Launch Spotify in the background, so it registers itself as a
 *     Connect device the same way opening it manually always has.
 *  2. A few seconds later, bring this app back to the front - waking
 *     another app unavoidably shows its own screen for a moment (Android
 *     has no API for silently starting a third-party app's process), so
 *     this step is what stops the person from being left looking at
 *     Spotify's own screen after a reboot instead of this one.
 *
 * The delay before step 1 exists because BOOT_COMPLETED can fire before
 * the system is fully ready to start new activities - launching
 * immediately can silently do nothing on some devices.
 *
 * Round 36, 2026-08-23 - see MainActivity.clearSystemBarsBeforeHandoff()'s
 * doc comment for the full diagnosis. In short: MainActivity is normally
 * already running (and already on-screen, as the Home app) by the time
 * this receiver's 8-second delay fires, with Round 33's immersive-sticky
 * system bar flags still active on its window - the same condition now
 * suspected of causing Spotify's freshly-created window to render blank.
 * Clears those flags on the live MainActivity instance (if one exists)
 * immediately before launching Spotify here too, for the same reason
 * wakeSpotifyApp() and restartApp() now do.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final long WAKE_SPOTIFY_DELAY_MS = 8000;
    private static final long RETURN_TO_KIOSK_DELAY_MS = 13000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        final Context appContext = context.getApplicationContext();
        Handler handler = new Handler();

        handler.postDelayed(new Runnable() {
            public void run() {
                try {
                    MainActivity.clearSystemBarsBeforeHandoffIfRunning();
                    Intent spotifyIntent = appContext.getPackageManager()
                            .getLaunchIntentForPackage(SPOTIFY_PACKAGE);
                    if (spotifyIntent != null) {
                        spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        appContext.startActivity(spotifyIntent);
                    }
                } catch (Exception ignored) {
                    // Spotify isn't installed, or the launch was rejected this
                    // early in boot - the in-app fallback
                    // (MainActivity.wakeSpotifyApp(), used when a play attempt
                    // finds no device) still covers this the next time someone
                    // taps Play.
                }
            }
        }, WAKE_SPOTIFY_DELAY_MS);

        handler.postDelayed(new Runnable() {
            public void run() {
                try {
                    Intent kioskIntent = new Intent(appContext, MainActivity.class);
                    kioskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    appContext.startActivity(kioskIntent);
                } catch (Exception ignored) {
                    // Nothing more to do if this fails - the system will
                    // still show whatever the home screen resolves to.
                }
            }
        }, RETURN_TO_KIOSK_DELAY_MS);
    }
}
