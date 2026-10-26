package com.seven7genanalytics.dancingwithdementia;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole app is one screen: three big transport buttons for the person
 * using the tablet, a small row of secondary controls (volume/skip), a
 * single Spotify playlist button and a separate audiobook button, plus a
 * discreet family-only setup panel (reached by a long press on a
 * near-invisible gear icon in the corner) for choosing the shared music
 * folder, connecting Spotify, and (if this app has been provisioned as
 * the tablet's Device Owner) toggling kiosk lock.
 *
 * Spotify playback is controlled entirely through Spotify's Web API
 * (plain HTTPS calls via HttpURLConnection + org.json) rather than
 * Spotify's App Remote SDK, because that SDK can only be fetched from
 * Maven/JCenter and this project is built completely offline with no
 * Gradle/Maven access (see spec.md). Login uses the OAuth Authorization
 * Code + PKCE flow. Rather than an embedded WebView (Google's own sign-in
 * option refuses to complete inside one) or relying on the tablet's own
 * browser (which may be too old to run Spotify's login page at all - seen
 * in practice on a test tablet), login happens on any capable device
 * the family already has, and the resulting code is typed/pasted back into
 * this app by hand. No client secret or extra library is needed.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "elderly_music_player";
    private static final String PREF_FOLDER_URI = "folder_uri";
    private static final String PREF_PERSON_NAME = "person_name";
    private static final int REQ_OPEN_TREE = 1001;
    private static final long SETUP_HOLD_MS = 1400;
    // How long to let a just-woken Spotify sit in the foreground before
    // this app reclaims it - see wakeSpotifyApp()/scheduleReturnToKiosk().
    private static final long RETURN_TO_KIOSK_DELAY_MS = 5000;
    // On the tablets this has actually been tested on (Neocore N1+,
    // Android 9), Spotify itself can be slow to finish starting up after a
    // cold boot - slower than the BootReceiver/wakeSpotifyApp() wake-up
    // mechanism alone accounts for. Rather than let the person hit a
    // confusing "couldn't start Spotify" the first time they try, show a
    // gentle "warming up" status for a window after boot instead of the
    // usual "Ready" - see maybeShowBootWarning().
    private static final long BOOT_WARNING_WINDOW_MS = 90000;

    private static final Pattern AUDIO_EXT = Pattern.compile(
            ".*\\.(mp3|m4a|aac|wav|ogg|oga|flac|wma)$", Pattern.CASE_INSENSITIVE);

    // ---------- Spotify constants ----------
    private static final String PREF_SPOTIFY_CLIENT_ID = "spotify_client_id";
    private static final String PREF_SPOTIFY_ACCESS_TOKEN = "spotify_access_token";
    private static final String PREF_SPOTIFY_REFRESH_TOKEN = "spotify_refresh_token";
    private static final String PREF_SPOTIFY_TOKEN_EXPIRY = "spotify_token_expiry";
    // The single music playlist. 2026-08-26: this app used to support up
    // to 3 named playlist buttons behind a drop-down (Playlist 1/2/3) -
    // the owner asked to remove that entirely and go down to just one
    // playlist, reached directly from the music button with no
    // picker in between (see spotifyToggleButton's listener below). The
    // preference key itself is kept as PREF_PLAYLIST1_* rather than
    // renamed, so an existing tablet's already-saved playlist keeps
    // working after this update instead of needing to be re-entered from
    // scratch - only the removed slots 2/3 are gone.
    private static final String PREF_PLAYLIST1_ID = "playlist1_id";
    private static final String PREF_PLAYLIST1_NAME = "playlist1_name";
    // Build 44, 2026-09-30: optional second and third music playlists,
    // switched on by the "More playlists" tick box in Family Setup
    // (PREF_MULTI_PLAYLISTS). With the box unticked the Music button plays
    // slot 1 directly, exactly as in Build 43. Ticked, with two or more
    // playlists saved, Music opens musicPickerPanel: up to three big named
    // choices, each playing on one tap. The owner wants this kept behind a
    // Family Setup option rather than on by default, alongside future
    // accessibility options. The keys reuse the old pre-2026-08-26
    // "playlist2_*"/"playlist3_*" names; any earlier values still on a
    // tablet simply show pre-filled once the box is ticked, where the family
    // can keep, edit or clear them. Unticking the box hides the extra slots
    // but keeps what was saved, so ticking it again brings them back.
    private static final String PREF_PLAYLIST2_ID = "playlist2_id";
    private static final String PREF_PLAYLIST2_NAME = "playlist2_name";
    private static final String PREF_PLAYLIST3_ID = "playlist3_id";
    private static final String PREF_PLAYLIST3_NAME = "playlist3_name";
    private static final String PREF_MULTI_PLAYLISTS = "multi_playlists_enabled";
    // Slot 4 is reserved for a single Spotify audiobook, reachable through
    // its own book-icon button rather than the note-icon playlist button
    // (see bookButton) - a different content type (an "audiobook" in Spotify's
    // API, not a "playlist") that the family links and names the same way.
    private static final String PREF_PLAYLIST4_ID = "playlist4_id";
    private static final String PREF_PLAYLIST4_NAME = "playlist4_name";
    // Round 38, 2026-08-23 - slot 4 turned out to need this: the owner's own
    // book link is a genuine Spotify PLAYLIST link
    // (open.spotify.com/playlist/...), not a true audiobook/show link, so
    // AUDIOBOOK_ID_PATTERN alone can never match it. Records which pattern
    // actually matched when slot 4 was saved, so playback knows whether to
    // use the show/audiobook context (with its resume-friendly fallback) or
    // a plain playlist context - see savePlaylistConfig() and
    // handlePlaylistButton()/handleSpotifyTransportPlay(). Missing (any
    // install from before this round that already has a real audiobook
    // saved) defaults to "audiobook" for backward compatibility.
    private static final String PREF_PLAYLIST4_TYPE = "playlist4_type";
    // Holds the PKCE code_verifier between "Log In to Spotify" (which opens
    // the system browser) and the redirect coming back into onNewIntent() -
    // an in-memory field isn't safe enough here since Android may reclaim
    // this Activity's process while the browser (and any password/2FA entry
    // in it) is in the foreground.
    private static final String PREF_SPOTIFY_PENDING_VERIFIER = "spotify_pending_verifier";
    private static final String PREF_SPOTIFY_DEVICE_ID = "spotify_device_id";
    private static final String PREF_SPOTIFY_DEVICE_NAME = "spotify_device_name";
    // Remembers which playlist the big Play/Stop/Back/Next buttons act on,
    // so families who use Spotify instead of (or as well as) a local
    // folder still get full use of the three big colour-coded transport
    // buttons - not just the small playlist/book buttons.
    private static final String PREF_LAST_PLAYLIST_ID = "spotify_last_playlist_id";
    // Whether PREF_LAST_PLAYLIST_ID above is a Spotify "playlist" or a
    // "audiobook" - needed to rebuild the right context_uri. Missing
    // (pre-audiobook installs) defaults to "playlist" for compatibility.
    private static final String PREF_LAST_CONTEXT_TYPE = "spotify_last_context_type";

    // ---------- Family/staff PIN (roadmap item 4) ----------
    // A single shared PIN for everyone who needs to get past the home
    // screen (family + care staff), not per-person codes - simplest to
    // set up and hand out verbally, and matches how the rest of Family
    // Setup already works (one shared area, not individual accounts).
    // Deliberately NOT required out of the box: if entry required a PIN
    // before one had ever been set, there'd be no way to get into Family
    // Setup at all to set the first one. So the gear long-press still
    // opens the PIN-free Tablet Menu, with no PIN prompt, until the family
    // sets a PIN from inside Family Setup - after that, both Family Setup
    // and Tablet Settings (2026-08-22: moved behind the PIN too, see
    // quickMenuPanel's note below) require it first. Restart App never
    // does - it's the one thing that must always be reachable with no PIN.
    private static final String PREF_PIN = "family_pin";
    private static final int PIN_LENGTH = 4;

    // A plain, always-reachable HTTPS page (not under our control) rather
    // than a custom URI scheme. This app has no reliable way to host a
    // public web page for Spotify to redirect to, and this tablet's own
    // browser can't run Spotify's login page anyway (see spec.md) - so
    // login happens on a different, capable device (phone/computer), and
    // the family copies whatever ends up in that browser's address bar
    // back into this app. A real HTTPS redirect guarantees the browser
    // shows the full destination URL (including ?code=...) in its address
    // bar, which a custom scheme does not reliably do on a device that
    // doesn't have this app installed.
    private static final String SPOTIFY_REDIRECT_URI = "https://example.com/";
    private static final String SPOTIFY_SCOPES = "user-read-playback-state user-modify-playback-state";
    private static final String SPOTIFY_AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SPOTIFY_API_BASE = "https://api.spotify.com/v1";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    // Matches "playlist" followed by a plain "/" or ":" separator, but also
    // an escaped "\/" (as it appears inside embedded JSON in a fetched HTML
    // page) or a URL-encoded "%2F" - see fetchLinkTarget()'s note on why a
    // short link's landing page needs to be searched this broadly rather
    // than just its Location header. The {15,30} length bound keeps this
    // from matching incidental "playlist/..." substrings in unrelated
    // tracking/analytics URLs that can appear on the same page.
    private static final Pattern PLAYLIST_ID_PATTERN =
            Pattern.compile("playlist(?:%2F|\\\\/|/|:)+([a-zA-Z0-9]{15,30})");
    // 2026-08-22 (Round 30): this used to only match ".../audiobook/<id>".
    // But playSpotifyPlaylist() below - written back in Round 12, long
    // before this pattern existed - already knew better: it plays an
    // audiobook by trying "spotify:show:<id>" FIRST and only falls back to
    // "spotify:audiobook:<id>" if that's rejected, because Spotify's
    // audiobook playback has long run on the same underlying "show"
    // (podcast) infrastructure, and most audiobook share links in the wild
    // are actually .../show/<id> links, not .../audiobook/<id> ones - the
    // newer "audiobook" URL type only covers some of the catalog. This
    // pattern never accounted for that, so any "show"-style audiobook link
    // (almost certainly what the owner had been pasting) could never match,
    // no matter how well the redirect-following or page-fetching worked -
    // three rounds (27-29) chased the fetch logic while the actual keyword
    // being searched for was the wrong one. Now matches either form.
    private static final Pattern AUDIOBOOK_ID_PATTERN =
            Pattern.compile("(?:audiobook|show)(?:%2F|\\\\/|/|:)+([a-zA-Z0-9]{15,30})");

    // ---------- Default config (Round 38, 2026-08-23) ----------
    // The owner supplies these so every tablet - the two already in use,
    // and any future one from the tablet donation guide - starts already
    // configured, with none of this needing to be typed in by hand on the
    // on-screen keyboard through Family Setup. See seedDefaultConfigIfFirstRun().
    // 2026-08-26: only one playlist default remains, now that the app only
    // supports a single playlist slot - see PREF_PLAYLIST1_ID's note above.
    // The values themselves live in res/values/config_defaults.xml, which
    // is committed EMPTY. Real values for an actual deployment go in
    // local/res/values/config_defaults.xml (never committed - see
    // .gitignore and README.md), which the build overlays on top of the
    // empty one. A build without that local file simply seeds nothing,
    // and Family Setup is used instead.
    // Round 38 used a key called "defaults_seeded_v1" for this same purpose
    // - already true on both tablets from that install, so this round uses
    // a new key rather than reusing it (see below).
    //
    // Round 39, 2026-08-23 - Round 38's seeding only filled in a field if it
    // was still empty, specifically so it could never clobber anything
    // the owner or a family member set by hand. That turned out to be the
    // wrong call here: both tablets already had playlist data saved from
    // many earlier rounds' manual Family Setup entry, so every field was
    // already non-empty and Round 38's seeding silently did nothing -
    // "pre fill never ran" from the owner's side, even though the code ran
    // exactly as designed. What was actually wanted is these specific values
    // applied now, once, regardless of what's currently saved. This needs
    // its own new flag (not PREF_DEFAULTS_SEEDED) because that one is
    // already true on both tablets from the Round 38 install, which would
    // otherwise make this a no-op too. Still gated to run only once ever -
    // any edit made afterward through Family Setup's own explicit Save
    // button is a normal save and is never touched by this again.
    private static final String PREF_DEFAULTS_APPLIED_V2 = "defaults_applied_v2";

    private static class Track {
        final Uri uri;
        final String name;
        Track(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private static class HttpResult {
        int code;
        String body;
    }

    // Views
    private LinearLayout buttonsContainer;
    private Button playButton, stopButton;
    private TextView nowPlayingText;
    private TextView statusText, trackTitleText, trackCounterText, emptyStateText, nameHeaderText;
    private ImageButton setupTrigger;
    private FrameLayout setupPanel;
    private Button chooseFolderButton, exitKioskButton, openHomeScreenButton, restartAppButton, closeSetupButton;
    private TextView folderStatusText, kioskNoteText;

    // Quick recovery menu (added Round 26) - see onSetupTriggerHeld() and
    // quickMenuPanel's note in activity_main.xml. openHomeScreenButton and
    // restartAppButton above are bound to this panel's buttons, not ones
    // inside setupPanel - see bindViews().
    private FrameLayout quickMenuPanel;
    private Button quickFamilySetupButton, quickCloseButton;
    // Build 45: "Build NN" at the foot of the Tablet Menu and Family Setup.
    private TextView quickMenuBuildText, setupBuildText;
    // Fix Spotify (Round 37) - see openSpotifyAppInfoScreen()'s note.
    private Button quickFixSpotifyButton;

    private LinearLayout secondaryControls;
    private Button volumeDownButton, volumeUpButton, backButton, skipButton;
    private EditText personNameInput;
    private Button saveNameButton;
    private AudioManager audioManager;

    // PIN lock views
    private FrameLayout pinPanel;
    private TextView pinDotsText, pinErrorText, pinExplainerText;
    private Button pinCancelButton, setPinButton, removePinButton;
    private Button[] pinDigitButtons;
    private Button pinKeyClear, pinKeyBack;
    private final StringBuilder pinEntryBuffer = new StringBuilder();
    // Two-step "Set PIN" flow: first entry, then a confirmation entry that
    // must match, so a family member setting the PIN on a small on-screen
    // keypad can't lock everyone out with a typo. null when not mid-flow.
    private String pinFirstEntry = null;
    private boolean settingPin = false;

    // What the PIN panel should do once a *verify* entry (not a Set/Change
    // PIN entry - that's tracked separately via settingPin) is correct.
    // Added 2026-08-22 so the same PIN screen can gate either destination -
    // previously it only ever led to Family Setup. Owner feedback: "Leaving the
    // app to get to android needs to require a pin" - Tablet Settings now
    // needs the same verify step Family Setup already had.
    private static final int PIN_PURPOSE_FAMILY_SETUP = 1;
    private static final int PIN_PURPOSE_TABLET_SETTINGS = 2;
    // Round 37, 2026-08-23 - same reasoning as PIN_PURPOSE_TABLET_SETTINGS:
    // this also leaves the app for Android's own UI (Spotify's App Info
    // screen), so it gets the same PIN gate.
    private static final int PIN_PURPOSE_FIX_SPOTIFY = 3;
    private int pendingPinPurpose = PIN_PURPOSE_FAMILY_SETUP;

    // Spotify views
    // 2026-08-26: spotifyToggleButton now plays the single configured
    // playlist directly on tap - see its listener in setupListeners().
    // The old "Playlist 1/2/3" drop-down (spotifyControls,
    // spotifyButton1/2/3) is gone; there is only ever one playlist now.
    private Button spotifyToggleButton;
    private Button bookButton;
    private EditText spotifyClientIdInput;
    private Button saveClientIdButton, spotifyLoginButton;
    private TextView spotifyLoginUrlText;
    private EditText spotifyCodeInput;
    private Button submitSpotifyCodeButton;
    private TextView spotifyStatusText;
    private TextView spotifyCurrentDeviceText;
    private Button refreshSpotifyDevicesButton;
    private LinearLayout spotifyDeviceListContainer;
    private EditText playlist1LinkInput, playlist1NameInput;
    private EditText playlist4LinkInput, playlist4NameInput;
    // Build 44: see PREF_PLAYLIST2_ID's note.
    private EditText playlist2LinkInput, playlist2NameInput;
    private EditText playlist3LinkInput, playlist3NameInput;
    private CheckBox multiPlaylistCheckBox;
    private LinearLayout extraPlaylistsContainer;
    private FrameLayout musicPickerPanel;
    private Button[] musicChoiceButtons;
    private Button musicPickerBackButton;
    private Button savePlaylistsButton;
    private TextView playlistsStatusText;
    // Build 44: see clearSpotifyDetails().
    private Button clearSpotifyButton;
    private boolean clearSpotifyArmed = false;

    // Playback state
    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;
    private boolean hasStartedOnce = false;
    private int currentIndex = -1;

    // Which source Stop/Back/Next (and the now-playing display) should
    // actually reflect. A family can have BOTH a local folder AND Spotify
    // playlist/book configured at once (mix.isEmpty() alone can't tell them
    // apart in that case) - this tracks whichever one was started most
    // recently.
    private static final int SOURCE_NONE = 0;
    private static final int SOURCE_LOCAL = 1;
    private static final int SOURCE_SPOTIFY = 2;
    private int activeSource = SOURCE_NONE;

    // Polls Spotify's "currently playing" endpoint so nowPlayingText (the
    // slot where Pause used to be) can show the real track/chapter and
    // artist/book name while Spotify is the active source. There's no push
    // notification for this over the plain Web API, only polling.
    private static final long NOW_PLAYING_POLL_INTERVAL_MS = 8000;
    private boolean nowPlayingPollingActive = false;
    private final Runnable nowPlayingPollRunnable = new Runnable() {
        public void run() {
            if (!nowPlayingPollingActive) return;
            pollSpotifyNowPlaying();
            handler.postDelayed(this, NOW_PLAYING_POLL_INTERVAL_MS);
        }
    };

    /** True when Stop/Back/Next should act on Spotify rather than the local player. */
    private boolean isSpotifyTheActiveSource() {
        return activeSource == SOURCE_SPOTIFY
                || (activeSource == SOURCE_NONE && mix.isEmpty());
    }
    private final List<Track> mix = new ArrayList<>();
    private PowerManager.WakeLock wakeLock;

    // Kiosk state
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private boolean isDeviceOwner = false;
    private boolean inLockTask = false;
    // Build 44: true only while someone has deliberately tapped "Exit Kiosk
    // Mode" in Family Setup, so onResume() leaves the tablet unlocked until
    // they tap "Re-enter Kiosk Mode" (or the app restarts). Tablet Settings
    // does NOT set this, so the lock comes back as soon as the app is
    // returned to - see ensureKioskLock().
    private boolean kioskExitRequested = false;

    private final Handler handler = new Handler();
    private Runnable holdRunnable;

    /**
     * Live reference to the running MainActivity instance, if any - added
     * Round 36 so BootReceiver (a different class, running from a
     * BroadcastReceiver with no Activity of its own) can reach
     * clearSystemBarsBeforeHandoff() below without a bound service or
     * broadcast round-trip. Cleared in onDestroy() so a destroyed Activity
     * is never mistaken for a live one. Safe to hold as a plain static
     * field here specifically because this Activity's whole lifecycle IS
     * the app's lifecycle (launchMode="singleInstance", the only Activity
     * this app has) - there is no second instance this could ever alias.
     */
    private static MainActivity instance;

    /**
     * Round 38, 2026-08-23, rewritten Round 39 (2026-08-23) - fills in the
     * Spotify Client ID and the playlist/audiobook slots with the owner's
     * own values, so a fresh install (or a factory-reset/newly donated
     * tablet, per the tablet donation guide) comes up already configured,
     * with nothing needing to be typed in through Family Setup's on-screen
     * keyboard.
     *
     * Round 38's version only filled in a field if it was still empty,
     * specifically so it could never clobber a value changed by hand.
     * Owner feedback: "the playlists didnt save" - both tablets already had
     * playlist data saved from many earlier rounds' manual Family Setup
     * entry, so every field was already non-empty and that version quietly
     * did nothing at all. This version force-applies these specific values
     * unconditionally, exactly once (guarded by PREF_DEFAULTS_APPLIED_V2,
     * a new key - the old PREF_DEFAULTS_SEEDED is already true on both
     * tablets from the Round 38 install, which would make the old guard a
     * no-op here too). After this one-time pass, any change made through
     * Family Setup's own explicit Save button is untouched by this again.
     *
     * 2026-08-26: only seeds one playlist slot now (PREF_PLAYLIST1_*) -
     * the old Playlist 2/3 slots are gone, see PREF_PLAYLIST1_ID's note.
     */
    private void seedDefaultConfigIfFirstRun() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_DEFAULTS_APPLIED_V2, false)) return;

        SharedPreferences.Editor editor = prefs.edit();
        putIfSet(editor, PREF_SPOTIFY_CLIENT_ID, getString(R.string.default_spotify_client_id));
        putIfSet(editor, PREF_PLAYLIST1_ID, getString(R.string.default_playlist1_id));
        putIfSet(editor, PREF_PLAYLIST1_NAME, getString(R.string.default_playlist1_name));
        putIfSet(editor, PREF_PLAYLIST4_ID, getString(R.string.default_playlist4_id));
        putIfSet(editor, PREF_PLAYLIST4_NAME, getString(R.string.default_playlist4_name));
        putIfSet(editor, PREF_PLAYLIST4_TYPE, getString(R.string.default_playlist4_type));
        editor.putBoolean(PREF_DEFAULTS_APPLIED_V2, true).apply();
    }

    /** Only seeds a value the build actually supplied (see config_defaults.xml). */
    private static void putIfSet(SharedPreferences.Editor editor, String key, String value) {
        if (value != null && value.trim().length() > 0) {
            editor.putString(key, value.trim());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        setContentView(R.layout.activity_main);
        bindViews();
        showBuildNumber();
        seedDefaultConfigIfFirstRun();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setupKiosk();
        showOverLockScreen();
        hideSystemBars();
        setupListeners();
        updateNameHeader();
        updateSpotifyStatus();
        updatePlaylistButtons();
        restoreSavedFolder();
        applyOrientation(getResources().getConfiguration().orientation);
        maybeShowBootWarning();
    }

    /**
     * Hides the tablet's own status bar and navigation bar ("immersive
     * sticky" mode). Round 33, 2026-08-22 - Owner feedback: "we need to remove
     * the tablets tool bar from the app home page otherwise anyone can
     * bypass the pin into the settings." Tracked this to setupKiosk()
     * above: applyKioskLock() - the thing that's supposed to hide the
     * status bar via DevicePolicyManager.setStatusBarDisabled() - returns
     * immediately and does nothing at all when isDeviceOwner is false. Per
     * Round 26's finding, the test tablets are sideloaded installs, not
     * provisioned as Device Owner (that needs a factory reset + adb, which
     * has had unresolved connectivity problems all session - see
     * roadmap.md's "Remote updates" section) - so on his actual hardware,
     * NONE of the status-bar/keyguard/lock-task protection in
     * applyKioskLock() was ever running, leaving the system status bar (and
     * on many devices, a navigation bar) fully visible and interactive on
     * the app's home screen, exactly as reported: swipe it down,
     * reach Settings, and the PIN gate on Family Setup/Tablet Settings
     * never even comes into play because Android's own navigation is being
     * used instead of anything in this app.
     *
     * setSystemUiVisibility() with these flags needs no special privilege -
     * unlike setStatusBarDisabled(), it works on every install, Device
     * Owner or not, so it's called unconditionally here rather than only
     * when isDeviceOwner. It hides both bars and keeps them hidden
     * ("sticky") even after the user interacts with the screen, only
     * reappearing briefly if someone deliberately swipes in from a screen
     * edge, then auto-hiding again - a real barrier for a casual bypass
     * attempt, though not as absolute as the Device-Owner-only
     * setStatusBarDisabled() would be. Various system events (a dialog
     * opening, the keyboard appearing, the PIN/setup panels toggling)
     * clear these flags, which is why onWindowFocusChanged() below
     * re-applies them every time the window regains focus - the standard
     * pattern for keeping immersive mode persistent rather than one-shot.
     *
     * Deliberately NOT also calling startLockTask() for non-Device-Owner
     * installs here - unlike setStatusBarDisabled(), "screen pinning"
     * without Device Owner behaves inconsistently across Android versions
     * and OEM skins (some require a Settings toggle enabled first, some
     * show an onboarding dialog, the unpin gesture varies), and there's no
     * way to verify any of that from this sandbox. Real Device Owner
     * provisioning remains the robust fix for the navigation-button side of
     * this (Home/Recents); this change only closes the status-bar half.
     *
     * Round 36, 2026-08-23: these flags are now also temporarily cleared
     * immediately before every Spotify launch (see
     * clearSystemBarsBeforeHandoff() below) - strong evidence points to
     * them being the actual cause of Spotify's window rendering blank on
     * these tablets. onWindowFocusChanged() still re-applies them
     * automatically the moment this window regains focus, so normal kiosk
     * behaviour resumes on its own with no extra bookkeeping.
     */
    /**
     * Build 44, 2026-09-30 - Owner feedback: "The screen lock keeps coming on
     * and i cant find an obvious way to turn it off."
     *
     * Asks Android to show this screen on top of the tablet's lock screen,
     * and to dismiss that lock screen when it is only a swipe (no PIN,
     * pattern or password set on the tablet itself). Waking the tablet then
     * goes straight back to the big buttons, with no swipe to unlock first.
     * Unlike setKeyguardDisabled() in applyKioskLock(), these window flags
     * need no Device Owner privilege, so they are set on every install.
     *
     * Deliberate limits:
     * - A SECURE tablet lock (PIN/pattern/password) is never bypassed.
     *   Android does not allow it, and it would be wrong to try: the fix
     *   for that is to set the tablet's own Screen lock to "None".
     * - The screen is NOT forced to stay on. Whether it sleeps is left to
     *   the tablet's own Display/Sleep setting (or "Stay awake while
     *   charging"), so a tablet on battery does not drain overnight.
     *
     * These flags are deprecated from Android 8.1 in favour of
     * setShowWhenLocked()/requestDismissKeyguard(), but still work with this
     * app's targetSdkVersion (23), which is why they are used here.
     */
    private void showOverLockScreen() {
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        } catch (Exception ignored) {
            // Non-fatal: the tablet simply shows its own lock screen as before.
        }
    }

    private void hideSystemBars() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Exception ignored) {
            // Not available/behaves differently on this OS version - non-fatal,
            // the rest of the app works fine either way.
        }
    }

    /**
     * Round 36, 2026-08-23 - Owner feedback, after Round 35's fix: still "i still
     * cant open spotify. the ui boots up blank" - but this time confirmed
     * happening via EVERY launch path (Restart App, the book/playlist
     * buttons' wakeSpotifyApp() fallback, and a fresh boot) and on BOTH
     * tablets, not just Restart App on one of them. That's materially new
     * evidence Round 35 didn't have. Round 35 only ever reasoned about
     * restartApp()'s own AlarmManager-vs-killProcess() timing, because
     * until Round 34's exact-alarm fix, Restart App was the only path
     * anyone had actually observed Spotify launch (successfully or not)
     * from. Now that it's confirmed blank from wakeSpotifyApp() too - the
     * direct, synchronous startActivity() call Round 35 itself held up as
     * the proven-working reference pattern - the AlarmManager theory stops
     * explaining the symptom: wakeSpotifyApp() has never used AlarmManager
     * and never kills this app's own process, so whatever is happening
     * here isn't about a launch racing a process death.
     *
     * The one thing genuinely common to all three paths (Restart App,
     * wakeSpotifyApp(), and BootReceiver's separate launch - since
     * MainActivity, and its window, is normally already alive and
     * on-screen as the Home app by the time BootReceiver's 8-second delay
     * fires) is Round 33's hideSystemBars(): immersive-sticky status/
     * navigation-bar flags, unconditionally active on this Activity's own
     * window since Round 33, and re-applied every time it regains focus.
     * Round 35 considered this and dismissed it, reasoning that
     * isDeviceOwner is false on both tablets, so Lock Task Mode never
     * engages, and without it there's no OS mechanism for one app's
     * systemUiVisibility flags to reach a different app's separate window -
     * true for stock, well-behaved Android. But immersive mode (especially
     * IMMERSIVE_STICKY combined with HIDE_NAVIGATION/FULLSCREEN) is also a
     * well-documented source of window-manager/compositor glitches on
     * lower-end and older OEM builds specifically around the moment a new
     * activity is created while it's active - exactly the class of
     * hardware here (two Neocore tablets, one "a little older" than the
     * other, both pre-Android-10 OEM skins). Given the symptom now
     * correlates with every path that launches Spotify while this window
     * is in that state, and started right when Round 33 introduced it,
     * this is the strongest lead available - and safe to try either way:
     * worst case the status bar flashes visible for a moment during the
     * handoff, a minor cosmetic cost next to a broken Spotify launch.
     *
     * Called immediately before every Spotify-launching startActivity()
     * call in this file (wakeSpotifyApp(), restartApp()) and, via the
     * static helper below, from BootReceiver. onWindowFocusChanged()
     * already re-applies hideSystemBars() the moment this window next
     * regains focus, so normal kiosk behaviour resumes on its own - no
     * extra state to track here.
     *
     * Not yet confirmed on real hardware - if the screen is still blank
     * after this, immersive mode is not the cause and the next thing to
     * try is checking whether getLaunchIntentForPackage(SPOTIFY_PACKAGE)
     * is even resolving the same Spotify install each time on these
     * specific tablets (e.g. two Spotify packages, or a stale/corrupt
     * install only on the older one) - see Round 34's note on this as the
     * next thing to check if the exact-alarm fix ever stopped working.
     */
    private void clearSystemBarsBeforeHandoff() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Exception ignored) {
            // Non-fatal - worst case hideSystemBars()'s flags simply stay
            // active and behaviour is unchanged from before Round 36.
        }
    }

    /** Static entry point for BootReceiver, which has no Activity instance of its own. */
    static void clearSystemBarsBeforeHandoffIfRunning() {
        if (instance != null) {
            instance.clearSystemBarsBeforeHandoff();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureKioskLock();
    }

    /**
     * Build 44, 2026-09-30. Found on the first Android 15 tablet: the app WAS
     * Device Owner (confirmed with `adb shell dpm list-owners`), yet the
     * bottom bar and lock screen still worked. The kiosk lock was only ever
     * switched on once, in setupKiosk() during onCreate(). Two gaps followed:
     *
     * 1. If the app was opened before `dpm set-device-owner` ran, it saw
     *    isDeviceOwner == false at that moment. As the tablet's home screen
     *    it then kept running without being recreated, so the lock never
     *    switched on.
     * 2. Tablet Settings calls applyKioskLock(false) to hand over to Android.
     *    Returning to the app resumed the same Activity, so nothing locked
     *    it again.
     *
     * This re-checks Device Owner status and the real lock state every time
     * the app comes to the front. Lock task is also more reliable when
     * started from a resumed Activity than from onCreate(). It does nothing
     * if someone has deliberately exited kiosk mode from Family Setup.
     */
    private void ensureKioskLock() {
        if (dpm == null || kioskExitRequested) return;
        boolean ownerNow = dpm.isDeviceOwnerApp(getPackageName());
        if (ownerNow && !isDeviceOwner) {
            // Became Device Owner while already running (gap 1 above).
            setupKiosk();
            return;
        }
        if (!isDeviceOwner) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
                applyKioskLock(true);
            }
        } catch (Exception ignored) {
            // Non-fatal: the bars stay hidden by hideSystemBars() regardless.
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /**
     * If this app is starting very soon after the tablet itself booted
     * (rather than e.g. just being reopened), Spotify is realistically
     * still catching up in the background even once it's been woken -
     * confirmed slow specifically on the Neocore N1+ (Android 9) this has
     * been tested on. Rather than let "Ready" sit there looking fully
     * working while Spotify quietly isn't, show a gentle "warming up"
     * status for a window after boot, then let it fall back to "Ready" on
     * its own if nothing else has updated it by then.
     */
    private void maybeShowBootWarning() {
        if (SystemClock.elapsedRealtime() >= BOOT_WARNING_WINDOW_MS) return;
        setStatus("Warming up…");
        handler.postDelayed(new Runnable() {
            public void run() {
                if ("Warming up…".equals(statusText.getText().toString())) {
                    setStatus("Ready");
                }
            }
        }, BOOT_WARNING_WINDOW_MS);
    }

    private void bindViews() {
        buttonsContainer = (LinearLayout) findViewById(R.id.buttonsContainer);
        playButton = (Button) findViewById(R.id.playButton);
        nowPlayingText = (TextView) findViewById(R.id.nowPlayingText);
        stopButton = (Button) findViewById(R.id.stopButton);
        statusText = (TextView) findViewById(R.id.statusText);
        nameHeaderText = (TextView) findViewById(R.id.nameHeaderText);
        trackTitleText = (TextView) findViewById(R.id.trackTitleText);
        trackCounterText = (TextView) findViewById(R.id.trackCounterText);
        emptyStateText = (TextView) findViewById(R.id.emptyStateText);
        setupTrigger = (ImageButton) findViewById(R.id.setupTrigger);
        setupPanel = (FrameLayout) findViewById(R.id.setupPanel);
        chooseFolderButton = (Button) findViewById(R.id.chooseFolderButton);
        exitKioskButton = (Button) findViewById(R.id.exitKioskButton);
        // Bound to the quick-menu buttons now, not ones inside setupPanel -
        // see quickMenuPanel's note in activity_main.xml.
        openHomeScreenButton = (Button) findViewById(R.id.quickHomeScreenButton);
        restartAppButton = (Button) findViewById(R.id.quickRestartButton);
        closeSetupButton = (Button) findViewById(R.id.closeSetupButton);
        folderStatusText = (TextView) findViewById(R.id.folderStatusText);
        kioskNoteText = (TextView) findViewById(R.id.kioskNoteText);

        quickMenuPanel = (FrameLayout) findViewById(R.id.quickMenuPanel);
        quickFamilySetupButton = (Button) findViewById(R.id.quickFamilySetupButton);
        quickFixSpotifyButton = (Button) findViewById(R.id.quickFixSpotifyButton);
        quickCloseButton = (Button) findViewById(R.id.quickCloseButton);
        quickMenuBuildText = (TextView) findViewById(R.id.quickMenuBuildText);
        setupBuildText = (TextView) findViewById(R.id.setupBuildText);

        secondaryControls = (LinearLayout) findViewById(R.id.secondaryControls);
        volumeDownButton = (Button) findViewById(R.id.volumeDownButton);
        volumeUpButton = (Button) findViewById(R.id.volumeUpButton);
        backButton = (Button) findViewById(R.id.backButton);
        skipButton = (Button) findViewById(R.id.skipButton);
        personNameInput = (EditText) findViewById(R.id.personNameInput);
        saveNameButton = (Button) findViewById(R.id.saveNameButton);

        pinPanel = (FrameLayout) findViewById(R.id.pinPanel);
        pinDotsText = (TextView) findViewById(R.id.pinDotsText);
        pinErrorText = (TextView) findViewById(R.id.pinErrorText);
        pinCancelButton = (Button) findViewById(R.id.pinCancelButton);
        pinExplainerText = (TextView) findViewById(R.id.pinExplainerText);
        setPinButton = (Button) findViewById(R.id.setPinButton);
        removePinButton = (Button) findViewById(R.id.removePinButton);
        pinDigitButtons = new Button[]{
                (Button) findViewById(R.id.pinKey0), (Button) findViewById(R.id.pinKey1),
                (Button) findViewById(R.id.pinKey2), (Button) findViewById(R.id.pinKey3),
                (Button) findViewById(R.id.pinKey4), (Button) findViewById(R.id.pinKey5),
                (Button) findViewById(R.id.pinKey6), (Button) findViewById(R.id.pinKey7),
                (Button) findViewById(R.id.pinKey8), (Button) findViewById(R.id.pinKey9)
        };
        pinKeyClear = (Button) findViewById(R.id.pinKeyClear);
        pinKeyBack = (Button) findViewById(R.id.pinKeyBack);

        spotifyToggleButton = (Button) findViewById(R.id.spotifyToggleButton);
        spotifyClientIdInput = (EditText) findViewById(R.id.spotifyClientIdInput);
        saveClientIdButton = (Button) findViewById(R.id.saveClientIdButton);
        spotifyLoginButton = (Button) findViewById(R.id.spotifyLoginButton);
        spotifyLoginUrlText = (TextView) findViewById(R.id.spotifyLoginUrlText);
        spotifyCodeInput = (EditText) findViewById(R.id.spotifyCodeInput);
        submitSpotifyCodeButton = (Button) findViewById(R.id.submitSpotifyCodeButton);
        spotifyStatusText = (TextView) findViewById(R.id.spotifyStatusText);
        spotifyCurrentDeviceText = (TextView) findViewById(R.id.spotifyCurrentDeviceText);
        refreshSpotifyDevicesButton = (Button) findViewById(R.id.refreshSpotifyDevicesButton);
        spotifyDeviceListContainer = (LinearLayout) findViewById(R.id.spotifyDeviceListContainer);
        playlist1LinkInput = (EditText) findViewById(R.id.playlist1LinkInput);
        playlist1NameInput = (EditText) findViewById(R.id.playlist1NameInput);
        playlist4LinkInput = (EditText) findViewById(R.id.playlist4LinkInput);
        playlist4NameInput = (EditText) findViewById(R.id.playlist4NameInput);
        playlist2LinkInput = (EditText) findViewById(R.id.playlist2LinkInput);
        playlist2NameInput = (EditText) findViewById(R.id.playlist2NameInput);
        playlist3LinkInput = (EditText) findViewById(R.id.playlist3LinkInput);
        playlist3NameInput = (EditText) findViewById(R.id.playlist3NameInput);
        multiPlaylistCheckBox = (CheckBox) findViewById(R.id.multiPlaylistCheckBox);
        extraPlaylistsContainer = (LinearLayout) findViewById(R.id.extraPlaylistsContainer);
        musicPickerPanel = (FrameLayout) findViewById(R.id.musicPickerPanel);
        musicChoiceButtons = new Button[] {
                (Button) findViewById(R.id.musicChoice1Button),
                (Button) findViewById(R.id.musicChoice2Button),
                (Button) findViewById(R.id.musicChoice3Button)
        };
        musicPickerBackButton = (Button) findViewById(R.id.musicPickerBackButton);
        bookButton = (Button) findViewById(R.id.bookButton);
        savePlaylistsButton = (Button) findViewById(R.id.savePlaylistsButton);
        playlistsStatusText = (TextView) findViewById(R.id.playlistsStatusText);
        clearSpotifyButton = (Button) findViewById(R.id.clearSpotifyButton);
    }

    private void setupKiosk() {
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MusicKioskAdminReceiver.class);
        isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        if (isDeviceOwner) {
            // Spotify is allowed alongside our own package so that, when a
            // playlist button needs to wake the Spotify app up (see
            // wakeSpotifyApp()), the tablet doesn't fall out of kiosk lock
            // entirely - the system UI, home button and other apps stay
            // blocked either way.
            dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName(), SPOTIFY_PACKAGE});
            makePermanentHomeApp();
            applyKioskLock(true);
            exitKioskButton.setVisibility(View.VISIBLE);
            kioskNoteText.setVisibility(View.GONE);
        } else {
            exitKioskButton.setVisibility(View.GONE);
            kioskNoteText.setVisibility(View.VISIBLE);
        }
        // openHomeScreenButton (now in quickMenuPanel) is NOT gated on
        // isDeviceOwner - unlike Exit Kiosk Mode, it needs no special
        // privilege (openTabletSettings()'s applyKioskLock(false) call is a
        // safe no-op when this app isn't Device Owner). Hiding it on
        // non-Device-Owner installs was the actual bug that left the owner
        // stuck with no way back to the tablet's home screen - see
        // openTabletSettings()'s note.
    }

    /**
     * Build 44, 2026-09-30 - Owner request: "make dancing HOME app."
     *
     * The manifest already offers this app as a Home app, but Android lets
     * anyone pick a different default in Settings, and a new launcher
     * installed by an update can ask again. As Device Owner, this makes the
     * choice permanent: pressing Home always comes back here, and the
     * setting can't be changed from the tablet. Tablet Settings still
     * works, because openTabletSettings() launches the stock launcher by
     * its exact name rather than through the Home default.
     *
     * Undone only by a factory reset, along with Device Owner itself.
     */
    private void makePermanentHomeApp() {
        try {
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(adminComponent, homeFilter,
                    new ComponentName(this, MainActivity.class));
        } catch (Exception ignored) {
            // Non-fatal: the family can still choose it in Settings, Default apps.
        }
    }

    /**
     * Turns the full set of Device-Owner kiosk protections on or off
     * together: Lock Task Mode (screen pinning), the disabled status bar,
     * and the disabled keyguard.
     */
    private void applyKioskLock(boolean locked) {
        if (!isDeviceOwner) return;
        if (locked) {
            try {
                dpm.setStatusBarDisabled(adminComponent, true);
            } catch (Exception ignored) {
                // Not available/allowed on this OS version - non-fatal.
            }
            try {
                dpm.setKeyguardDisabled(adminComponent, true);
            } catch (Exception ignored) {
                // Not available/allowed on this OS version - non-fatal.
            }
            startLockTask();
            inLockTask = true;
            exitKioskButton.setText("Exit Kiosk Mode");
        } else {
            if (inLockTask) {
                stopLockTask();
                inLockTask = false;
            }
            try {
                dpm.setStatusBarDisabled(adminComponent, false);
            } catch (Exception ignored) {
                // Not available/allowed on this OS version - non-fatal.
            }
            try {
                dpm.setKeyguardDisabled(adminComponent, false);
            } catch (Exception ignored) {
                // Not available/allowed on this OS version - non-fatal.
            }
            exitKioskButton.setText("Re-enter Kiosk Mode");
        }
    }

    /**
     * Hands control back to the tablet's own Android home screen. Queries
     * every activity on the device that can handle ACTION_MAIN/CATEGORY_HOME
     * (queryIntentActivities() ignores the user's "Always" default, unlike
     * startActivity() with an implicit intent) and explicitly launches
     * whichever one isn't this app - normally the tablet's original stock
     * launcher, still installed underneath this one.
     *
     * As of 2026-08-22, reaching this action at all requires the Family PIN
     * first if one is set (see quickHomeScreenButton's listener) - owner feedback:
     * "Leaving the app to get to android needs to require a pin." Note this
     * does remove the no-PIN safety valve Round 26 added for a family that's
     * forgotten its own PIN; the OS-level fallback documented in spec.md
     * (notification shade -> Settings -> Apps -> Default apps -> Home ->
     * pick the stock launcher) still works independently of anything in
     * this app, and stays the way back in if that ever happens.
     */
    private void openTabletSettings() {
        applyKioskLock(false);
        try {
            Intent homeQuery = new Intent(Intent.ACTION_MAIN);
            homeQuery.addCategory(Intent.CATEGORY_HOME);
            PackageManager pm = getPackageManager();
            List<ResolveInfo> candidates = pm.queryIntentActivities(homeQuery, 0);
            ResolveInfo otherLauncher = null;
            for (ResolveInfo info : candidates) {
                if (info.activityInfo != null && !getPackageName().equals(info.activityInfo.packageName)) {
                    otherLauncher = info;
                    break;
                }
            }
            if (otherLauncher != null) {
                Intent goHome = new Intent(Intent.ACTION_MAIN);
                goHome.addCategory(Intent.CATEGORY_HOME);
                goHome.setClassName(otherLauncher.activityInfo.packageName, otherLauncher.activityInfo.name);
                goHome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(goHome);
            } else {
                // No other launcher found on this device at all - Settings
                // is still a more useful landing spot than doing nothing.
                Intent settingsIntent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(settingsIntent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open the Android home screen automatically - try rebooting "
                    + "the tablet, which should show it briefly before this app takes back over.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Round 37, 2026-08-23 - The second test tablet was showing a solid
     * blank (black/white) Spotify window on every launch trigger and on
     * both tablets. Round 36's theory (this app's own immersive system-bar
     * flags stuck on-screen underneath Spotify's new window) turned out to
     * be wrong: dumpsys/logcat evidence showed Spotify's window genuinely
     * being created, focused and marked "Displayed" by the window manager
     * with no crash or ANR anywhere - it just never painted anything past
     * that. "adb shell pm clear com.spotify.music" (wiping Spotify's own
     * app data/cache) fixed it immediately, which points at corrupted
     * Spotify app state, not anything in this app's code or window flags.
     *
     * There's no public Android API for one ordinary app to clear another
     * app's data on someone else's behalf - DevicePolicyManager only
     * exposes clearApplicationUserData() to a Device Owner, and neither of
     * the test tablets is provisioned that way (see isDeviceOwner). The
     * closest thing an ordinary app CAN do is jump straight to that app's
     * own "App info" screen in Android Settings, which is exactly what
     * this does - it saves whoever's fixing this a trip through Settings'
     * own app list, but the final "Clear storage" tap still has to be done
     * by hand, by design (Android doesn't let an app confirm that itself).
     *
     * PIN-gated like Tablet Settings, for the same reason: it leaves this
     * app for Android's own UI.
     */
    private void openSpotifyAppInfoScreen() {
        try {
            // Build 44: Android's Settings app is not on the lock-task allow
            // list, so the kiosk lock would block this screen. Release it
            // first, as Tablet Settings does; ensureKioskLock() puts it back
            // when the app is returned to.
            applyKioskLock(false);
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + SPOTIFY_PACKAGE));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this,
                    "On the Spotify app info screen: tap \"Storage & cache\", then \"Clear storage\". "
                            + "Press the Home button when done to come back here.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open Spotify's app info screen automatically - "
                    + "from Android Settings, go to Apps, find Spotify, then Storage & cache, "
                    + "then Clear storage.", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Restarts just this app's own process. There's no Android API to
     * simply "restart my own app" - schedules a fresh launch of
     * MainActivity a moment in the future via AlarmManager, then kills this
     * process outright, which is what actually clears a wedged
     * view/thread/native-player state that a plain recreate() wouldn't.
     *
     * Round 33, 2026-08-22 - Owner feedback: "The tablet needs to boot spotify and
     * the app on restart (as app wont play without spotify open)."
     * BootReceiver already wakes Spotify correctly after a real device
     * power-cycle, but this button - Restart App, the one recovery action
     * that's always reachable with no PIN, so the one actually used
     * day-to-day - only ever relaunched this app itself. Spotify stayed
     * closed until someone happened to tap Play afterwards and
     * playSpotifyPlaylist()'s reactive wakeSpotifyApp() fallback kicked in
     * on that failed attempt - exactly the "won't play" symptom described.
     * Now schedules a Spotify launch via AlarmManager too, alongside the
     * existing app-relaunch alarm - it has to go through AlarmManager
     * rather than a Handler.postDelayed() here, since those alarms are
     * owned by the OS and outlive the killProcess() call below, whereas an
     * in-process delayed Runnable would simply never fire. No long delay
     * needed the way BootReceiver's 8-second cushion is - unlike a real
     * power cycle, the OS and Spotify's own process are both already warm
     * here, so both alarms fire close together.
     *
     * Round 34, 2026-08-22 - Owner feedback: "spotify is still not launching."
     * Both alarms were using AlarmManager.set(), which is an INEXACT alarm
     * as of Android 4.4+, and on Android 6.0+ (this app's own
     * minSdkVersion) inexact alarms from an app that was just killed (see
     * the killProcess() call right after these) are exactly the kind of
     * thing App Standby/Doze power management is designed to defer -
     * sometimes by several seconds, sometimes much longer, with no
     * guarantee at all. That matches "still not launching" better than any
     * bug in the Spotify-launch logic itself, which is the same call
     * already working elsewhere in this app (wakeSpotifyApp(),
     * BootReceiver). Switched both to setExactAndAllowWhileIdle(), which
     * is exempt from that deferral - requires the RTC_WAKEUP variant
     * rather than plain RTC (a wakeUp type is required by this specific
     * method; harmless here since the screen is already on when someone
     * taps this button).
     *
     * Round 35, 2026-08-23 - Owner feedback: "why is the spotify app now blank?"
     * Compared against Round 31, when Spotify was last known to work fine.
     * Round 32 was layout-only. Round 33 added two things: hideSystemBars()
     * and this method's AlarmManager-based Spotify wake. Diagnosed the
     * AlarmManager wake as the difference and switched to a direct,
     * synchronous startActivity() call here, matching wakeSpotifyApp()'s
     * pattern - executed first and immediately, before scheduling anything
     * or killing this process.
     *
     * Round 36, 2026-08-23 - Owner feedback: still blank, and this time confirmed
     * via every launch path (this one, wakeSpotifyApp(), and a fresh boot),
     * on both tablets - see clearSystemBarsBeforeHandoff()'s doc comment
     * for the full reasoning. That ruled out Round 35's AlarmManager theory
     * (wakeSpotifyApp() never used AlarmManager and still went blank) and
     * pointed instead at Round 33's hideSystemBars() immersive flags, still
     * active on this window at the moment Spotify's activity is created.
     * Added a clearSystemBarsBeforeHandoff() call here too, right before
     * the same startActivity() call Round 35 already made direct and
     * synchronous.
     */
    private void restartApp() {
        Intent spotifyIntent = getPackageManager().getLaunchIntentForPackage(SPOTIFY_PACKAGE);
        if (spotifyIntent != null) {
            clearSystemBarsBeforeHandoff();
            spotifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(spotifyIntent);
            } catch (Exception ignored) { }
        }

        Intent restartIntent = new Intent(this, MainActivity.class);
        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 500, pendingIntent);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void setupListeners() {
        playButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { handlePlay(); }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { handleStop(); }
        });
        backButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { handleBack(); }
        });
        backButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { handleBackLongPress(); return true; }
        });
        skipButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { handleSkip(); }
        });
        volumeUpButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            }
        });
        volumeDownButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            }
        });

        // 2026-08-26: plays the single configured playlist directly on tap.
        // Build 44: still does, unless "More playlists" is ticked in Family
        // Setup and two or more playlists are saved - then it opens the
        // music choice screen instead. See onMusicButtonTapped().
        spotifyToggleButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onMusicButtonTapped(); }
        });
        for (int i = 0; i < musicChoiceButtons.length; i++) {
            final int slot = i;
            musicChoiceButtons[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    musicPickerPanel.setVisibility(View.GONE);
                    handlePlaylistButton(slot);
                }
            });
        }
        musicPickerBackButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { musicPickerPanel.setVisibility(View.GONE); }
        });
        // Tapping the dark background around the choices also closes it.
        musicPickerPanel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { musicPickerPanel.setVisibility(View.GONE); }
        });
        // Saved straight away (no need to press Save Playlists) so the home
        // screen reflects it as soon as Family Setup is closed.
        multiPlaylistCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_MULTI_PLAYLISTS, isChecked).apply();
                extraPlaylistsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                updatePlaylistButtons();
            }
        });
        bookButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { handlePlaylistButton(3); }
        });

        setupTrigger.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        holdRunnable = new Runnable() {
                            public void run() { onSetupTriggerHeld(); }
                        };
                        handler.postDelayed(holdRunnable, SETUP_HOLD_MS);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (holdRunnable != null) handler.removeCallbacks(holdRunnable);
                        return true;
                }
                return false;
            }
        });

        chooseFolderButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQ_OPEN_TREE);
            }
        });

        exitKioskButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!isDeviceOwner) return;
                kioskExitRequested = inLockTask;
                applyKioskLock(inLockTask ? false : true);
            }
        });

        // 2026-08-22: Tablet Settings now goes through the same PIN gate as
        // Family Setup, if a PIN is set - Owner feedback: "Leaving the app to get
        // to android needs to require a pin." Previously this called
        // openTabletSettings() directly with no PIN check at all (Round 26
        // made it deliberately PIN-free as a recovery hatch; this reverses
        // that per the owner's explicit request).
        openHomeScreenButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                quickMenuPanel.setVisibility(View.GONE);
                if (getSavedPin().length() > 0) {
                    openPinPanel(PIN_PURPOSE_TABLET_SETTINGS);
                } else {
                    openTabletSettings();
                }
            }
        });

        // Restart App stays the one action that's always reachable with no
        // PIN check at all, on purpose - see quickMenuPanel's note.
        restartAppButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { restartApp(); }
        });

        closeSetupButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setupPanel.setVisibility(View.GONE); }
        });

        // Family Setup, reached from the quick menu, stays PIN-gated exactly
        // as before.
        quickFamilySetupButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                quickMenuPanel.setVisibility(View.GONE);
                if (getSavedPin().length() > 0) {
                    openPinPanel(PIN_PURPOSE_FAMILY_SETUP);
                } else {
                    openSetupPanel();
                }
            }
        });

        // Fix Spotify (Round 37) - PIN-gated the same way as Tablet Settings,
        // since it also leaves this app for Android's own Settings UI.
        quickFixSpotifyButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                quickMenuPanel.setVisibility(View.GONE);
                if (getSavedPin().length() > 0) {
                    openPinPanel(PIN_PURPOSE_FIX_SPOTIFY);
                } else {
                    openSpotifyAppInfoScreen();
                }
            }
        });

        quickCloseButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { quickMenuPanel.setVisibility(View.GONE); }
        });

        saveNameButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String name = personNameInput.getText().toString().trim();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_PERSON_NAME, name)
                        .apply();
                updateNameHeader();
            }
        });

        saveClientIdButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String clientId = spotifyClientIdInput.getText().toString().trim();
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_SPOTIFY_CLIENT_ID, clientId)
                        .apply();
                spotifyStatusText.setText("Client ID saved.");
            }
        });

        spotifyLoginButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startSpotifyLogin(); }
        });

        submitSpotifyCodeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { submitSpotifyCode(); }
        });

        refreshSpotifyDevicesButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshSpotifyDevices(); }
        });

        savePlaylistsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { savePlaylistConfig(); }
        });
        // First tap arms it and asks for confirmation; a second tap within
        // 5 seconds clears. Anything else (or waiting) disarms it.
        clearSpotifyButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!clearSpotifyArmed) {
                    clearSpotifyArmed = true;
                    clearSpotifyButton.setText("Tap again to clear");
                    handler.postDelayed(new Runnable() {
                        public void run() { disarmClearSpotify(); }
                    }, 5000);
                    return;
                }
                disarmClearSpotify();
                clearSpotifyDetails();
            }
        });

        setupPinListeners();
    }

    private void openSetupPanel() {
        quickMenuPanel.setVisibility(View.GONE);
        musicPickerPanel.setVisibility(View.GONE);
        String savedName = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_PERSON_NAME, "");
        personNameInput.setText(savedName);
        updateSpotifyStatus();
        updatePinSection();
        populatePlaylistInputs();
        setupPanel.setVisibility(View.VISIBLE);
    }

    private void updateNameHeader() {
        String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_PERSON_NAME, "");
        if (name != null && name.trim().length() > 0) {
            nameHeaderText.setText(name.trim() + "’s Music");
            nameHeaderText.setVisibility(View.VISIBLE);
        } else {
            nameHeaderText.setVisibility(View.GONE);
        }
        // Build 42: keep the music button's label in step with the saved
        // name too (it used to show fixed text forever).
        updatePlaylistButtonLabel();
    }

    /**
     * Build 42: the book and music buttons show a large icon and one plain
     * word - "Book" and "Music" - rather than a name. (The old music label
     * was fixed text that never matched the saved name.
     * The owner then asked for just "Book" and "Music" with more prominent
     * icons; the person's name still shows in the header at the top.)
     *
     * Called from updateNameHeader() and updatePlaylistButtons(), both of
     * which run from onCreate() only after bindViews(); the null checks are
     * a safety net.
     */
    private void updatePlaylistButtonLabel() {
        if (spotifyToggleButton != null) {
            spotifyToggleButton.setText(iconLabel("\u266A", "Music"));
        }
        if (bookButton != null) {
            // 📖 = U+1F4D6 OPEN BOOK, written as a UTF-16 surrogate pair.
            bookButton.setText(iconLabel("\uD83D\uDCD6", "Book"));
        }
    }

    /** Icon drawn about twice the size of the word beside it, so it reads first. */
    private static CharSequence iconLabel(String icon, String word) {
        android.text.SpannableString label = new android.text.SpannableString(icon + "  " + word);
        label.setSpan(new android.text.style.RelativeSizeSpan(1.9f), 0, icon.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    // ---------- Build number (Build 45) ----------

    /**
     * Shows which build this tablet is running, as "Build 45", at the foot
     * of the Tablet Menu and of Family Setup. Whoever is helping by phone
     * can then ask "which build is it?" and compare it with the releases
     * page. The number is read from android:versionName in
     * AndroidManifest.xml, so it is always the build actually installed and
     * never needs updating by hand. The Tablet Menu copy needs no PIN, so a
     * carer can read it out without being able to change anything.
     */
    private void showBuildNumber() {
        String label;
        try {
            label = "Build " + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // Cannot happen for our own package, but never crash over a label.
            label = "";
        }
        if (quickMenuBuildText != null) quickMenuBuildText.setText(label);
        if (setupBuildText != null) setupBuildText.setText(label);
    }

    // ---------- Family/staff PIN (roadmap item 4) ----------

    /**
     * Called on the long-press gesture. Opens the PIN-free quick recovery
     * menu (Round 26) rather than going straight to the PIN panel or
     * Family Setup - Restart App needs to stay reachable without the
     * Family PIN, since the PIN itself (or this app having taken over as
     * the tablet's Home app) could be exactly what's got someone stuck.
     * Tablet Settings and Family Setup are both still reachable from this
     * menu, but each is PIN-gated (if a PIN is set) - see their listeners
     * in setupListeners().
     */
    private void onSetupTriggerHeld() {
        openQuickMenuPanel();
    }

    private void openQuickMenuPanel() {
        setupPanel.setVisibility(View.GONE);
        pinPanel.setVisibility(View.GONE);
        quickMenuPanel.setVisibility(View.VISIBLE);
    }

    private String getSavedPin() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PIN, "");
    }

    private void setupPinListeners() {
        View.OnClickListener digitListener = new View.OnClickListener() {
            public void onClick(View v) {
                for (int i = 0; i < pinDigitButtons.length; i++) {
                    if (v == pinDigitButtons[i]) {
                        onPinDigit(String.valueOf(i));
                        return;
                    }
                }
            }
        };
        for (Button b : pinDigitButtons) {
            b.setOnClickListener(digitListener);
        }
        pinKeyClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pinEntryBuffer.setLength(0);
                updatePinDots();
            }
        });
        pinKeyBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (pinEntryBuffer.length() > 0) {
                    pinEntryBuffer.deleteCharAt(pinEntryBuffer.length() - 1);
                    updatePinDots();
                }
            }
        });
        pinCancelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closePinPanel(); }
        });
        setPinButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { beginSetPinFlow(); }
        });
        removePinButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .remove(PREF_PIN)
                        .apply();
                updatePinSection();
            }
        });
    }

    /**
     * Entry gate: verifying the existing PIN before either Family Setup or
     * Tablet Settings opens. `purpose` records which one to route to once
     * the PIN is confirmed correct - see onPinEntryComplete().
     */
    private void openPinPanel(int purpose) {
        pendingPinPurpose = purpose;
        settingPin = false;
        pinFirstEntry = null;
        pinEntryBuffer.setLength(0);
        pinErrorText.setVisibility(View.GONE);
        updatePinDots();
        // setupPanel/quickMenuPanel should already be hidden at this point
        // (reached from the quick-menu buttons, which hide quickMenuPanel
        // themselves) - hidden here too, defensively, so pinPanel can never
        // end up rendered underneath either regardless of call order.
        setupPanel.setVisibility(View.GONE);
        quickMenuPanel.setVisibility(View.GONE);
        pinPanel.setVisibility(View.VISIBLE);
    }

    private void closePinPanel() {
        // beginSetPinFlow() (Set/Change PIN, launched from inside Family
        // Setup) hides setupPanel while the keypad is up - restore it on
        // the way out so Cancel returns to Family Setup rather than
        // dropping back to the bare main screen. openPinPanel()'s verify
        // flow never had setupPanel visible in the first place, so this is
        // a no-op there.
        boolean returnToSetup = settingPin;
        pinPanel.setVisibility(View.GONE);
        pinEntryBuffer.setLength(0);
        pinFirstEntry = null;
        settingPin = false;
        if (returnToSetup) {
            setupPanel.setVisibility(View.VISIBLE);
        }
    }

    /** Launched from the "Set PIN"/"Change PIN" button inside Family Setup. */
    private void beginSetPinFlow() {
        settingPin = true;
        pinFirstEntry = null;
        pinEntryBuffer.setLength(0);
        pinErrorText.setVisibility(View.GONE);
        updatePinDots();
        // THE FIX (Round 17): Family Setup is declared after pinPanel in
        // the layout, so it draws (and captures touches) on top of it -
        // without this, tapping "Set PIN" showed the keypad underneath a
        // still-visible, still-touchable Family Setup screen. Hiding it
        // here, and restoring it in closePinPanel(), fixes that.
        setupPanel.setVisibility(View.GONE);
        pinPanel.setVisibility(View.VISIBLE);
    }

    private void onPinDigit(String digit) {
        if (pinEntryBuffer.length() >= PIN_LENGTH) return;
        pinEntryBuffer.append(digit);
        updatePinDots();
        if (pinEntryBuffer.length() == PIN_LENGTH) {
            handler.postDelayed(new Runnable() {
                public void run() { onPinEntryComplete(); }
            }, 150);
        }
    }

    private void updatePinDots() {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < PIN_LENGTH; i++) {
            dots.append(i < pinEntryBuffer.length() ? "●" : "○");
            if (i < PIN_LENGTH - 1) dots.append(" ");
        }
        pinDotsText.setText(dots.toString());
    }

    private void onPinEntryComplete() {
        String entered = pinEntryBuffer.toString();

        if (settingPin) {
            // Two-step confirmation so a mistyped new PIN on a small
            // on-screen keypad can't accidentally lock everyone out.
            if (pinFirstEntry == null) {
                pinFirstEntry = entered;
                pinEntryBuffer.setLength(0);
                updatePinDots();
                pinErrorText.setVisibility(View.GONE);
                return;
            }
            if (entered.equals(pinFirstEntry)) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_PIN, entered)
                        .apply();
                closePinPanel();
                updatePinSection();
                openSetupPanel();
            } else {
                pinFirstEntry = null;
                pinEntryBuffer.setLength(0);
                pinErrorText.setText("Those didn't match - enter a new PIN again.");
                pinErrorText.setVisibility(View.VISIBLE);
                updatePinDots();
            }
            return;
        }

        // Verifying against the existing PIN, gating entry into whichever
        // screen was requested (Family Setup or Tablet Settings - see
        // openPinPanel()/pendingPinPurpose).
        if (entered.equals(getSavedPin())) {
            int purpose = pendingPinPurpose;
            closePinPanel();
            if (purpose == PIN_PURPOSE_TABLET_SETTINGS) {
                openTabletSettings();
            } else if (purpose == PIN_PURPOSE_FIX_SPOTIFY) {
                openSpotifyAppInfoScreen();
            } else {
                openSetupPanel();
            }
        } else {
            pinEntryBuffer.setLength(0);
            pinErrorText.setText("Wrong PIN - try again.");
            pinErrorText.setVisibility(View.VISIBLE);
            updatePinDots();
        }
    }

    /** Keeps the PIN section of Family Setup in sync with whether one is currently set. */
    private void updatePinSection() {
        boolean hasPin = getSavedPin().length() > 0;
        setPinButton.setText(hasPin ? "Change PIN" : "Set PIN");
        removePinButton.setVisibility(hasPin ? View.VISIBLE : View.GONE);
        pinExplainerText.setText(hasPin
                ? "A PIN is set. Family and staff need it to get past the home screen after the long-press, and to reach Tablet Settings. Change it here, or remove it to go back to no PIN at all."
                : "No PIN is set yet - anyone who finds the gear icon can get in here. Set a 4-digit PIN so only family and staff who know it can reach Family Setup and Tablet Settings after the long-press.");
    }

    // ---------- Folder loading ----------

    private void restoreSavedFolder() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(PREF_FOLDER_URI, null);
        if (saved != null) {
            loadFolder(Uri.parse(saved));
        }
    }

    private void loadFolder(final Uri treeUri) {
        folderStatusText.setText("Loading music…");
        new Thread(new Runnable() {
            public void run() {
                final List<Track> found = new ArrayList<Track>();
                try {
                    String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
                    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
                    Cursor cursor = getContentResolver().query(childrenUri, new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    }, null, null, null);

                    if (cursor != null) {
                        try {
                            while (cursor.moveToNext()) {
                                String docId = cursor.getString(0);
                                String name = cursor.getString(1);
                                String mime = cursor.getString(2);
                                if (isAudio(name, mime)) {
                                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                                    found.add(new Track(docUri, friendlyName(name)));
                                }
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                } catch (Exception e) {
                    // Permission may have been revoked (e.g. folder deleted/moved) - treat as empty.
                }

                Collections.shuffle(found);

                runOnUiThread(new Runnable() {
                    public void run() {
                        mix.clear();
                        mix.addAll(found);
                        currentIndex = -1;
                        hasStartedOnce = false;
                        releaseMediaPlayer();

                        boolean empty = mix.isEmpty();
                        updateTransportButtonsEnabled();
                        stopButton.setEnabled(false);
                        updateEmptyState();
                        trackTitleText.setVisibility(View.GONE);
                        trackCounterText.setVisibility(View.GONE);
                        statusText.setText("Ready");
                        folderStatusText.setText(empty
                                ? "No playable music files were found in that folder."
                                : (mix.size() + " songs loaded."));
                    }
                });
            }
        }).start();
    }

    private static boolean isAudio(String name, String mime) {
        if (name == null) return false;
        if (mime != null && mime.startsWith("audio/")) return true;
        return AUDIO_EXT.matcher(name).matches();
    }

    private static String friendlyName(String filename) {
        String base = filename.replaceAll("(?i)\\.(mp3|m4a|aac|wav|ogg|oga|flac|wma)$", "");
        base = base.replaceAll("[_\\-]+", " ").trim();
        return base.isEmpty() ? filename : base;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_TREE && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_FOLDER_URI, treeUri.toString())
                    .apply();
            loadFolder(treeUri);
        }
    }

    // ---------- Playback ----------

    private void handlePlay() {
        if (isSpotifyTheActiveSource()) {
            boolean resuming = (activeSource == SOURCE_SPOTIFY);
            activeSource = SOURCE_SPOTIFY;
            if (resuming) {
                handleSpotifyResume();
            } else {
                handleSpotifyTransportPlay();
            }
            return;
        }
        activeSource = SOURCE_LOCAL;
        stopNowPlayingPolling();
        pauseSpotifyBestEffort();
        if (!hasStartedOnce) {
            hasStartedOnce = true;
            playIndex(0);
        } else if (mediaPlayer != null && isPrepared) {
            mediaPlayer.start();
            acquireWakeLock();
            setStatus("Playing");
        } else {
            playIndex(currentIndex < 0 ? 0 : currentIndex);
        }
    }

    private void handleStop() {
        if (isSpotifyTheActiveSource()) {
            handleSpotifyTransportStop();
            return;
        }
        if (mediaPlayer != null && isPrepared) {
            mediaPlayer.pause();
            releaseWakeLock();
            setStatus("Stopped");
        }
    }

    private void handleBack() {
        if (isSpotifyTheActiveSource()) {
            handleSpotifyTransportBack();
            return;
        }
        hasStartedOnce = true;
        stopButton.setEnabled(true);
        playIndex(currentIndex - 1);
    }

    private void handleBackLongPress() {
        if (isSpotifyTheActiveSource()) {
            handleSpotifyBackLongPress();
            return;
        }
        if (mix.isEmpty()) return;
        hasStartedOnce = true;
        stopButton.setEnabled(true);
        playIndex(0);
    }

    private void handleSkip() {
        if (isSpotifyTheActiveSource()) {
            handleSpotifyTransportSkip();
            return;
        }
        hasStartedOnce = true;
        stopButton.setEnabled(true);
        playIndex(currentIndex + 1);
    }

    private void playIndex(int index) {
        if (mix.isEmpty()) return;
        currentIndex = ((index % mix.size()) + mix.size()) % mix.size();
        Track track = mix.get(currentIndex);

        releaseMediaPlayer();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer mp) {
                isPrepared = true;
                mp.start();
                acquireWakeLock();
                setStatus("Playing");
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer mp) { playIndex(currentIndex + 1); }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                // Skip a track that fails to play rather than getting stuck.
                playIndex(currentIndex + 1);
                return true;
            }
        });

        stopButton.setEnabled(true);
        updateNowPlayingUI();

        try {
            mediaPlayer.setDataSource(this, track.uri);
            mediaPlayer.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            playIndex(currentIndex + 1);
        }
    }

    private void updateNowPlayingUI() {
        if (currentIndex < 0 || currentIndex >= mix.size()) return;
        Track track = mix.get(currentIndex);
        trackTitleText.setText(track.name);
        trackTitleText.setVisibility(View.VISIBLE);
        trackCounterText.setText("Song " + (currentIndex + 1) + " of " + mix.size());
        trackCounterText.setVisibility(View.VISIBLE);
        nowPlayingText.setText(track.name);
    }

    private void setStatus(String status) {
        statusText.setText(status);
    }

    private void updateTransportButtonsEnabled() {
        boolean canPlay = !mix.isEmpty() || hasAnySpotifyPlaylistConfigured();
        playButton.setEnabled(canPlay);
        backButton.setEnabled(canPlay);
        skipButton.setEnabled(canPlay);
    }

    private void updateEmptyState() {
        boolean nothingConfigured = mix.isEmpty() && !hasAnySpotifyPlaylistConfigured();
        emptyStateText.setVisibility(nothingConfigured ? View.VISIBLE : View.GONE);
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.setOnPreparedListener(null);
            mediaPlayer.setOnCompletionListener(null);
            mediaPlayer.setOnErrorListener(null);
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        isPrepared = false;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "ElderlyMusicPlayer::Playback");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    protected void onDestroy() {
        releaseMediaPlayer();
        releaseWakeLock();
        stopNowPlayingPolling();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ---------- Spotify: login (Authorization Code + PKCE, manual code hand-off) ----------

    private void startSpotifyLogin() {
        String clientId = spotifyClientIdInput.getText().toString().trim();
        if (clientId.length() == 0) {
            spotifyStatusText.setText("Enter a Client ID first.");
            return;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_SPOTIFY_CLIENT_ID, clientId)
                .apply();

        try {
            String verifier = generateRandomUrlSafeString(64);
            String challenge = generateCodeChallenge(verifier);
            String state = generateRandomUrlSafeString(16);

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_SPOTIFY_PENDING_VERIFIER, verifier)
                    .apply();

            String url = SPOTIFY_AUTHORIZE_URL
                    + "?client_id=" + URLEncoder.encode(clientId, "UTF-8")
                    + "&response_type=code"
                    + "&redirect_uri=" + URLEncoder.encode(SPOTIFY_REDIRECT_URI, "UTF-8")
                    + "&code_challenge_method=S256"
                    + "&code_challenge=" + URLEncoder.encode(challenge, "UTF-8")
                    + "&scope=" + URLEncoder.encode(SPOTIFY_SCOPES, "UTF-8")
                    + "&state=" + URLEncoder.encode(state, "UTF-8");

            spotifyLoginUrlText.setText(url);
            spotifyLoginUrlText.setVisibility(View.VISIBLE);
            spotifyStatusText.setText("Link ready below. Trying to open it here too…");

            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
            } catch (Exception ignored) {
                // Fine - the selectable link text below is the reliable path.
            }
        } catch (Exception e) {
            spotifyStatusText.setText("Couldn't build the login link.");
        }
    }

    private void submitSpotifyCode() {
        String raw = spotifyCodeInput.getText().toString();
        final String code = parseAuthCode(raw);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String verifier = prefs.getString(PREF_SPOTIFY_PENDING_VERIFIER, "");

        if (code == null) {
            spotifyStatusText.setText("Couldn't find a code in that - paste the code, "
                    + "or the whole web address from the page Spotify sent you to.");
            return;
        }
        if (verifier.length() == 0) {
            spotifyStatusText.setText("Tap \"Get Login Link\" first, then come back and submit the code.");
            return;
        }

        spotifyStatusText.setText("Connecting…");
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = exchangeCodeForToken(code, verifier);
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateSpotifyStatus();
                        if (ok) {
                            spotifyCodeInput.setText("");
                            spotifyLoginUrlText.setVisibility(View.GONE);
                        } else {
                            spotifyStatusText.setText("Spotify login failed - check the Client ID, "
                                    + "get a fresh login link, and try again.");
                        }
                    }
                });
            }
        }).start();
    }

    private static String parseAuthCode(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.length() == 0) return null;

        Matcher m = Pattern.compile("[?&]code=([^&\\s]+)").matcher(input);
        if (m.find()) {
            try {
                return java.net.URLDecoder.decode(m.group(1), "UTF-8");
            } catch (Exception e) {
                return m.group(1);
            }
        }
        if (input.startsWith("http") || input.contains(" ")) return null;
        return input;
    }

    private boolean exchangeCodeForToken(String code, String verifier) {
        try {
            String clientId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_SPOTIFY_CLIENT_ID, "").trim();
            String body = "grant_type=authorization_code"
                    + "&code=" + URLEncoder.encode(code, "UTF-8")
                    + "&redirect_uri=" + URLEncoder.encode(SPOTIFY_REDIRECT_URI, "UTF-8")
                    + "&client_id=" + URLEncoder.encode(clientId, "UTF-8")
                    + "&code_verifier=" + URLEncoder.encode(verifier, "UTF-8");

            HttpResult result = httpPostForm(SPOTIFY_TOKEN_URL, body);
            if (result.code != 200) return false;
            saveTokens(new JSONObject(result.body));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean refreshAccessToken() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String refreshToken = prefs.getString(PREF_SPOTIFY_REFRESH_TOKEN, "");
            String clientId = prefs.getString(PREF_SPOTIFY_CLIENT_ID, "").trim();
            if (refreshToken.length() == 0 || clientId.length() == 0) return false;

            String body = "grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
                    + "&client_id=" + URLEncoder.encode(clientId, "UTF-8");

            HttpResult result = httpPostForm(SPOTIFY_TOKEN_URL, body);
            if (result.code != 200) return false;
            saveTokens(new JSONObject(result.body));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveTokens(JSONObject json) throws JSONException {
        String accessToken = json.getString("access_token");
        long expiresIn = json.optLong("expires_in", 3600);
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(PREF_SPOTIFY_ACCESS_TOKEN, accessToken);
        editor.putLong(PREF_SPOTIFY_TOKEN_EXPIRY, System.currentTimeMillis() + (expiresIn * 1000L) - 60000L);
        if (json.has("refresh_token")) {
            editor.putString(PREF_SPOTIFY_REFRESH_TOKEN, json.optString("refresh_token", ""));
        }
        editor.apply();
    }

    private String getValidAccessToken() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String token = prefs.getString(PREF_SPOTIFY_ACCESS_TOKEN, "");
        long expiry = prefs.getLong(PREF_SPOTIFY_TOKEN_EXPIRY, 0);
        if (token.length() == 0) return null;
        if (System.currentTimeMillis() >= expiry) {
            if (!refreshAccessToken()) return null;
            token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SPOTIFY_ACCESS_TOKEN, "");
        }
        return token.length() > 0 ? token : null;
    }

    private void updateSpotifyStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean connected = prefs.getString(PREF_SPOTIFY_REFRESH_TOKEN, "").length() > 0;
        spotifyStatusText.setText(connected ? "Connected to Spotify." : "Not connected to Spotify.");
        spotifyClientIdInput.setText(prefs.getString(PREF_SPOTIFY_CLIENT_ID, ""));
        updateCurrentDeviceText();
    }

    private void updateCurrentDeviceText() {
        String name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SPOTIFY_DEVICE_NAME, "");
        spotifyCurrentDeviceText.setText(name.length() > 0
                ? "Playlists will play on: " + name
                : "No device selected yet - open Spotify on the tablet, then tap Refresh Devices List below.");
    }

    private static class SpotifyDevice {
        final String id;
        final String name;
        final String type;
        SpotifyDevice(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    private void refreshSpotifyDevices() {
        spotifyDeviceListContainer.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Looking for devices…");
        loading.setTextColor(0xFF9AA0AB);
        spotifyDeviceListContainer.addView(loading);

        new Thread(new Runnable() {
            public void run() {
                final List<SpotifyDevice> devices = new ArrayList<SpotifyDevice>();
                String error = null;
                String token = getValidAccessToken();
                if (token == null) {
                    error = "Log in to Spotify first.";
                } else {
                    try {
                        HttpResult result = httpGet(SPOTIFY_API_BASE + "/me/player/devices", token);
                        if (result.code == 200) {
                            JSONArray arr = new JSONObject(result.body).optJSONArray("devices");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject d = arr.getJSONObject(i);
                                    devices.add(new SpotifyDevice(
                                            d.optString("id", ""),
                                            d.optString("name", "Unknown device"),
                                            d.optString("type", "")));
                                }
                            }
                        } else {
                            error = "Couldn't reach Spotify - check the tablet is online.";
                        }
                    } catch (Exception e) {
                        error = "Couldn't reach Spotify - check the tablet is online.";
                    }
                }
                final String finalError = error;
                runOnUiThread(new Runnable() {
                    public void run() { showSpotifyDeviceList(devices, finalError); }
                });
            }
        }).start();
    }

    private void showSpotifyDeviceList(List<SpotifyDevice> devices, String error) {
        if (error == null) {
            lastFetchedDevices = devices;
        }
        spotifyDeviceListContainer.removeAllViews();
        if (error != null) {
            TextView errText = new TextView(this);
            errText.setText(error);
            errText.setTextColor(0xFFE27272);
            spotifyDeviceListContainer.addView(errText);
            return;
        }
        if (devices.isEmpty()) {
            TextView noneText = new TextView(this);
            noneText.setText("No devices found. Open the Spotify app on the tablet, wait a few seconds, then tap Refresh again.");
            noneText.setTextColor(0xFF9AA0AB);
            spotifyDeviceListContainer.addView(noneText);
            return;
        }
        String savedId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SPOTIFY_DEVICE_ID, "");
        for (final SpotifyDevice device : devices) {
            Button b = new Button(this);
            boolean selected = device.id.length() > 0 && device.id.equals(savedId);
            b.setText((selected ? "✓ " : "") + device.name + " (" + device.type + ")");
            b.setTextColor(0xFFFFFFFF);
            b.setAllCaps(false);
            b.setBackgroundColor(selected ? 0xFF1F7D38 : 0xFF333844);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) dp(8);
            b.setLayoutParams(lp);
            b.setPadding((int) dp(24), (int) dp(14), (int) dp(24), (int) dp(14));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectSpotifyDevice(device); }
            });
            spotifyDeviceListContainer.addView(b);
        }
    }

    private void selectSpotifyDevice(SpotifyDevice device) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_SPOTIFY_DEVICE_ID, device.id)
                .putString(PREF_SPOTIFY_DEVICE_NAME, device.name)
                .apply();
        updateCurrentDeviceText();
        showSpotifyDeviceList(lastKnownDevices(), null);
    }

    private List<SpotifyDevice> lastFetchedDevices = new ArrayList<SpotifyDevice>();

    private List<SpotifyDevice> lastKnownDevices() {
        return lastFetchedDevices;
    }

    private static String generateRandomUrlSafeString(int numBytes) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[numBytes];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes("UTF-8"));
        return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // ---------- Spotify: playlist configuration ----------

    private void disarmClearSpotify() {
        clearSpotifyArmed = false;
        if (clearSpotifyButton != null) clearSpotifyButton.setText("Clear Spotify details");
    }

    /**
     * Build 44, 2026-09-30 - Owner request: Build 43 tablets came pre-filled
     * with the maintainer's own Spotify Client ID, playlist and book (from
     * the private local/ overlay, see config_defaults.xml). Build 44 is
     * built without them, but a tablet upgraded from 43 keeps what it saved.
     *
     * This removes everything Spotify-related from the tablet: login tokens, chosen playback device, all playlist and book slots,
     * the "More playlists" option and what was last playing. The person's
     * name and the Family PIN are kept. PREF_DEFAULTS_APPLIED_V2 is left
     * set, so nothing is re-filled on the next start.
     *
     * The Client ID is reset to the one built into this build, if any
     * (config_defaults.xml): for the trial every tablet shares the
     * project's Spotify developer app, so the installer never has to type
     * it. Only a Client ID someone entered by hand is removed.
     *
     * Also the way to hand a tablet on to a new resident. It does not sign
     * the Spotify app itself out; do that in the Spotify app if the account
     * is changing too.
     */
    private void clearSpotifyDetails() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_SPOTIFY_CLIENT_ID)
                .remove(PREF_SPOTIFY_ACCESS_TOKEN)
                .remove(PREF_SPOTIFY_REFRESH_TOKEN)
                .remove(PREF_SPOTIFY_TOKEN_EXPIRY)
                .remove(PREF_SPOTIFY_PENDING_VERIFIER)
                .remove(PREF_SPOTIFY_DEVICE_ID)
                .remove(PREF_SPOTIFY_DEVICE_NAME)
                .remove(PREF_PLAYLIST1_ID).remove(PREF_PLAYLIST1_NAME)
                .remove(PREF_PLAYLIST2_ID).remove(PREF_PLAYLIST2_NAME)
                .remove(PREF_PLAYLIST3_ID).remove(PREF_PLAYLIST3_NAME)
                .remove(PREF_PLAYLIST4_ID).remove(PREF_PLAYLIST4_NAME)
                .remove(PREF_PLAYLIST4_TYPE)
                .remove(PREF_MULTI_PLAYLISTS)
                .remove(PREF_LAST_PLAYLIST_ID)
                .remove(PREF_LAST_CONTEXT_TYPE)
                .apply();
        String builtInClientId = getString(R.string.default_spotify_client_id).trim();
        if (builtInClientId.length() > 0) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_SPOTIFY_CLIENT_ID, builtInClientId).apply();
        }
        spotifyCodeInput.setText("");
        spotifyLoginUrlText.setText("");
        spotifyDeviceListContainer.removeAllViews();
        populatePlaylistInputs();
        updateSpotifyStatus();
        updateCurrentDeviceText();
        updatePlaylistButtons();
        playlistsStatusText.setText("Spotify details cleared. Set up again from the top of this section.");
    }

    private void populatePlaylistInputs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        setPlaylistInputIfSaved(playlist1LinkInput, playlist1NameInput, PREF_PLAYLIST1_ID, PREF_PLAYLIST1_NAME, "playlist", prefs);
        setPlaylistInputIfSaved(playlist2LinkInput, playlist2NameInput, PREF_PLAYLIST2_ID, PREF_PLAYLIST2_NAME, "playlist", prefs);
        setPlaylistInputIfSaved(playlist3LinkInput, playlist3NameInput, PREF_PLAYLIST3_ID, PREF_PLAYLIST3_NAME, "playlist", prefs);
        boolean multi = prefs.getBoolean(PREF_MULTI_PLAYLISTS, false);
        multiPlaylistCheckBox.setChecked(multi);
        extraPlaylistsContainer.setVisibility(multi ? View.VISIBLE : View.GONE);
        // Round 38: redisplay slot 4 as whichever type was actually saved
        // (see PREF_PLAYLIST4_TYPE) rather than assuming "audiobook".
        setPlaylistInputIfSaved(playlist4LinkInput, playlist4NameInput, PREF_PLAYLIST4_ID, PREF_PLAYLIST4_NAME,
                prefs.getString(PREF_PLAYLIST4_TYPE, "audiobook"), prefs);
    }

    private void setPlaylistInputIfSaved(EditText linkInput, EditText nameInput,
                                          String idKey, String nameKey, String uriType, SharedPreferences prefs) {
        String id = prefs.getString(idKey, "");
        String name = prefs.getString(nameKey, "");
        linkInput.setText(id.length() > 0 ? ("spotify:" + uriType + ":" + id) : "");
        nameInput.setText(name);
    }

    /**
     * Rewritten 2026-08-22 (network-fetch/short-link handling), and again
     * 2026-08-26 to drop the two removed playlist slots. Family Setup now
     * has exactly two music slots to save: the single playlist
     * (PREF_PLAYLIST1_*) and the audiobook (PREF_PLAYLIST4_*) - see
     * PREF_PLAYLIST1_ID's field comment for why the audiobook keeps its
     * historical "4" naming even though it's the second (and last) slot
     * now.
     *
     * Runs on a background thread since resolving a pasted link that isn't
     * an immediate regex match may need a network fetch (see
     * fetchLinkTargetDiag()) - the button disables itself and the status
     * text says "Saving…" until it's done. Failures are reported per-field
     * and specifically (missing name vs. missing link vs. a link that
     * still doesn't resolve) rather than one generic message.
     */
    private void savePlaylistConfig() {
        // Build 44: four slots now - playlists 1-3 and the audiobook (last).
        // Playlists 2 and 3 are only read and saved while "More playlists"
        // is ticked; unticked, whatever they held before is left alone.
        final boolean multi = multiPlaylistCheckBox.isChecked();
        final String[] rawLinks = {
                playlist1LinkInput.getText().toString().trim(),
                playlist2LinkInput.getText().toString().trim(),
                playlist3LinkInput.getText().toString().trim(),
                playlist4LinkInput.getText().toString().trim()
        };
        final String[] rawNames = {
                playlist1NameInput.getText().toString().trim(),
                playlist2NameInput.getText().toString().trim(),
                playlist3NameInput.getText().toString().trim(),
                playlist4NameInput.getText().toString().trim()
        };
        final String[] idKeys = {PREF_PLAYLIST1_ID, PREF_PLAYLIST2_ID, PREF_PLAYLIST3_ID, PREF_PLAYLIST4_ID};
        final String[] nameKeys = {PREF_PLAYLIST1_NAME, PREF_PLAYLIST2_NAME, PREF_PLAYLIST3_NAME, PREF_PLAYLIST4_NAME};
        final boolean[] isAudiobookSlot = {false, false, false, true};
        final boolean[] skipSlot = {false, !multi, !multi, false};
        final String[] slotLabel = {
                multi ? "Playlist 1" : "Playlist", "Playlist 2", "Playlist 3", "Audiobook"};
        final int slotCount = rawLinks.length;
        final int bookIndex = slotCount - 1;

        playlistsStatusText.setText("Saving…");
        savePlaylistsButton.setEnabled(false);

        new Thread(new Runnable() {
            public void run() {
                final String[] resolvedIds = new String[slotCount];
                // Tracks which context type actually matched for the
                // audiobook slot (bookIndex) - see PREF_PLAYLIST4_TYPE's note.
                final String[] resolvedTypes = new String[slotCount];
                final String[] problems = new String[slotCount];
                int savedCount = 0;

                for (int i = 0; i < slotCount; i++) {
                    if (skipSlot[i]) continue;
                    String rawLink = rawLinks[i];
                    String name = rawNames[i];

                    if (rawLink.length() == 0 && name.length() == 0) {
                        resolvedIds[i] = "";
                        continue;
                    }

                    String id;
                    String type;
                    if (isAudiobookSlot[i]) {
                        // Try a true audiobook/show link first (keeps the
                        // resume-friendly show/audiobook context for anyone
                        // who genuinely has one), then fall back to a plain
                        // playlist link.
                        id = extractAudiobookId(rawLink);
                        type = "audiobook";
                        if (id == null) {
                            id = extractPlaylistId(rawLink);
                            type = "playlist";
                        }
                    } else {
                        id = extractPlaylistId(rawLink);
                        type = "playlist";
                    }
                    String extraDetail = "";

                    if (id == null && looksLikeUrl(rawLink)) {
                        FetchDiag diag = fetchLinkTargetDiag(rawLink);
                        if (diag.error != null) {
                            extraDetail = " Tried to open that link but couldn't reach it (" + diag.error
                                    + ") - check the tablet is online and try again.";
                        } else if (diag.content != null) {
                            if (isAudiobookSlot[i]) {
                                id = extractAudiobookId(diag.content);
                                type = "audiobook";
                                if (id == null) {
                                    id = extractPlaylistId(diag.content);
                                    type = "playlist";
                                }
                            } else {
                                id = extractPlaylistId(diag.content);
                                type = "playlist";
                            }
                            if (id == null) {
                                extraDetail = " Opened that link fine (HTTP " + diag.httpCode + ", got "
                                        + diag.content.length() + " characters back) but couldn't find a Spotify "
                                        + (isAudiobookSlot[i] ? "audiobook or playlist" : "playlist")
                                        + " address anywhere in what came back.";
                            }
                        }
                    }

                    if (id != null && name.length() > 0) {
                        resolvedIds[i] = id;
                        resolvedTypes[i] = type;
                        savedCount++;
                    } else {
                        resolvedIds[i] = "";
                        if (rawLink.length() == 0) {
                            problems[i] = slotLabel[i] + " needs a link.";
                        } else if (name.length() == 0) {
                            problems[i] = slotLabel[i] + " needs a button name.";
                        } else {
                            String preview = rawLink.length() > 60 ? rawLink.substring(0, 60) + "…" : rawLink;
                            problems[i] = slotLabel[i] + "'s link (\"" + preview + "\") doesn't look like a Spotify "
                                    + (isAudiobookSlot[i] ? "audiobook or playlist" : "playlist") + " link." + extraDetail;
                        }
                    }
                }

                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                for (int i = 0; i < slotCount; i++) {
                    if (skipSlot[i]) continue;
                    editor.putString(idKeys[i], resolvedIds[i]);
                    editor.putString(nameKeys[i], resolvedIds[i].length() > 0 ? rawNames[i] : "");
                }
                editor.putString(PREF_PLAYLIST4_TYPE,
                        resolvedIds[bookIndex].length() > 0 ? resolvedTypes[bookIndex] : "");
                editor.apply();

                final int finalSavedCount = savedCount;
                final StringBuilder problemText = new StringBuilder();
                for (String p : problems) {
                    if (p != null) {
                        if (problemText.length() > 0) problemText.append(" ");
                        problemText.append(p);
                    }
                }

                runOnUiThread(new Runnable() {
                    public void run() {
                        updatePlaylistButtons();
                        savePlaylistsButton.setEnabled(true);
                        if (problemText.length() > 0) {
                            playlistsStatusText.setText(finalSavedCount + " item(s) saved. " + problemText.toString());
                        } else {
                            playlistsStatusText.setText(finalSavedCount + " item"
                                    + (finalSavedCount == 1 ? "" : "s") + " saved.");
                        }
                    }
                });
            }
        }).start();
    }

    private static boolean looksLikeUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static class FetchDiag {
        String content;
        String error;
        int httpCode;
    }

    private static FetchDiag fetchLinkTargetDiag(String url) {
        FetchDiag diag = new FetchDiag();
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36");
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                if (code >= 300 && code < 400 && location != null && location.length() > 0) {
                    current = new URL(new URL(current), location).toString();
                    InputStream skipStream = conn.getErrorStream();
                    if (skipStream != null) {
                        try { skipStream.close(); } catch (Exception ignored) { }
                    }
                    continue;
                }
                InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                String body = readStreamStatic(stream);
                diag.httpCode = code;
                diag.content = (body != null && body.length() > 0) ? body : current;
                return diag;
            } catch (Exception e) {
                diag.error = e.getClass().getSimpleName()
                        + (e.getMessage() != null && e.getMessage().length() > 0 ? ": " + e.getMessage() : "");
                return diag;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        diag.content = current;
        return diag;
    }

    /** Static twin of readStream() - needed here since fetchLinkTarget() runs as a static helper. */
    private static String readStreamStatic(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        int totalChars = 0;
        while ((line = reader.readLine()) != null && totalChars < 200000) {
            sb.append(line);
            totalChars += line.length();
        }
        reader.close();
        return sb.toString();
    }

    private static String extractPlaylistId(String input) {
        if (input == null || input.length() == 0) return null;
        Matcher m = PLAYLIST_ID_PATTERN.matcher(input);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String extractAudiobookId(String input) {
        if (input == null || input.length() == 0) return null;
        Matcher m = AUDIOBOOK_ID_PATTERN.matcher(input);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 2026-08-26: rewritten for a single playlist button. There's no
     * picker/drop-down any more (see spotifyToggleButton's field comment) -
     * this just shows or hides the one playlist button, and separately the
     * audiobook button, based on whether each has both a link and a name
     * saved.
     */
    private void updatePlaylistButtons() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Build 44: with "More playlists" ticked, Music shows if ANY of the
        // three playlist slots is set up, not just slot 1.
        boolean playlistVisible = !getConfiguredMusicSlots().isEmpty();
        updatePlaylistButtonLabel();
        spotifyToggleButton.setVisibility(playlistVisible ? View.VISIBLE : View.GONE);

        String bookId = prefs.getString(PREF_PLAYLIST4_ID, "");
        String bookName = prefs.getString(PREF_PLAYLIST4_NAME, "");
        boolean bookVisible = bookId.length() > 0 && bookName.length() > 0;
        // Build 42: the book button's label ("Book" with a large icon) is set
        // by updatePlaylistButtonLabel() above, not from the saved book name.
        bookButton.setVisibility(bookVisible ? View.VISIBLE : View.GONE);

        updateTransportButtonsEnabled();
        updateEmptyState();
        sizeButtons();
    }

    private boolean hasAnySpotifyPlaylistConfigured() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return !getConfiguredMusicSlots().isEmpty()
                || prefs.getString(PREF_PLAYLIST4_ID, "").length() > 0;
    }

    private String getFirstConfiguredPlaylistId() {
        List<Integer> slots = getConfiguredMusicSlots();
        if (slots.isEmpty()) return null;
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(MUSIC_ID_KEYS[slots.get(0)], "");
    }

    // Build 44: music playlist slots 0-2, in the order they appear in
    // Family Setup and on the music choice screen.
    private static final String[] MUSIC_ID_KEYS = {PREF_PLAYLIST1_ID, PREF_PLAYLIST2_ID, PREF_PLAYLIST3_ID};
    private static final String[] MUSIC_NAME_KEYS = {PREF_PLAYLIST1_NAME, PREF_PLAYLIST2_NAME, PREF_PLAYLIST3_NAME};

    /**
     * Build 44: the music slots (0-2) that have both a link and a name
     * saved. Only slot 0 counts unless "More playlists" is ticked, so an
     * unticked tablet behaves exactly like Build 43 even if slots 1-2 still
     * hold earlier values.
     */
    private List<Integer> getConfiguredMusicSlots() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int maxSlots = prefs.getBoolean(PREF_MULTI_PLAYLISTS, false) ? MUSIC_ID_KEYS.length : 1;
        List<Integer> slots = new ArrayList<Integer>();
        for (int i = 0; i < maxSlots; i++) {
            if (prefs.getString(MUSIC_ID_KEYS[i], "").length() > 0
                    && prefs.getString(MUSIC_NAME_KEYS[i], "").length() > 0) {
                slots.add(i);
            }
        }
        return slots;
    }

    /**
     * Build 44: what the Music button does. One playlist set up (always
     * the case with "More playlists" unticked) plays it straight away, as
     * before. Two or three set up opens the music choice screen, showing
     * only the slots that are actually filled in, sized to fit the screen.
     */
    private void onMusicButtonTapped() {
        List<Integer> slots = getConfiguredMusicSlots();
        if (slots.isEmpty()) return;
        if (slots.size() == 1) {
            handlePlaylistButton(slots.get(0));
            return;
        }
        showMusicPicker(slots);
    }

    private void showMusicPicker(List<Integer> slots) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Leave room for the heading, the Back button and margins, then
        // share what's left between the choices, capped so two choices on
        // a big screen don't become absurdly tall.
        float density = getResources().getDisplayMetrics().density;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int available = screenHeight - (int) (220 * density);
        int buttonHeight = Math.min((int) (150 * density),
                Math.max((int) (64 * density), available / slots.size() - (int) (16 * density)));
        float textSp = Math.max(20f, Math.min(34f, buttonHeight / density * 0.26f));

        for (int i = 0; i < musicChoiceButtons.length; i++) {
            Button b = musicChoiceButtons[i];
            if (slots.contains(i)) {
                b.setText(iconLabel("\u266A", prefs.getString(MUSIC_NAME_KEYS[i], "")));
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
                lp.height = buttonHeight;
                b.setLayoutParams(lp);
                b.setTextSize(textSp);
                b.setVisibility(View.VISIBLE);
            } else {
                b.setVisibility(View.GONE);
            }
        }
        musicPickerPanel.setVisibility(View.VISIBLE);
    }

    // ---------- Spotify: playback ----------

    /**
     * slot 0 is the single music playlist (PREF_PLAYLIST1_*); slot 3 is
     * the audiobook (PREF_PLAYLIST4_*, kept under its historical key name
     * - see PREF_PLAYLIST1_ID's note). Both spotifyToggleButton and
     * bookButton call this directly on tap now - see setupListeners().
     */
    private void handlePlaylistButton(int slot) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // Build 44: slots 0-2 are music playlists 1-3.
        final String playlistId = (slot == 3)
                ? prefs.getString(PREF_PLAYLIST4_ID, "")
                : prefs.getString(MUSIC_ID_KEYS[slot], "");
        if (playlistId.length() == 0) return;

        // Round 38: slot 3 (the book) isn't necessarily a true audiobook -
        // see PREF_PLAYLIST4_TYPE's note above. Defaults to "audiobook" for
        // installs from before this round that already have a real one saved.
        final String contextType = (slot == 3) ? prefs.getString(PREF_PLAYLIST4_TYPE, "audiobook") : "playlist";

        startSpotifyPlaylistPlayback(playlistId, contextType);
    }

    private void handleSpotifyTransportPlay() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String playlistId = prefs.getString(PREF_LAST_PLAYLIST_ID, "");
        String contextType = prefs.getString(PREF_LAST_CONTEXT_TYPE, "playlist");
        if (playlistId.length() == 0) {
            playlistId = getFirstConfiguredPlaylistId();
            contextType = "playlist";
            if (playlistId == null) {
                String bookId = prefs.getString(PREF_PLAYLIST4_ID, "");
                if (bookId.length() > 0) {
                    playlistId = bookId;
                    contextType = prefs.getString(PREF_PLAYLIST4_TYPE, "audiobook");
                }
            }
        }
        if (playlistId == null) {
            setStatus("Choose a playlist above first.");
            return;
        }
        startSpotifyPlaylistPlayback(playlistId, contextType);
    }

    private void startSpotifyPlaylistPlayback(final String playlistId, final String contextType) {
        activeSource = SOURCE_SPOTIFY;
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_LAST_PLAYLIST_ID, playlistId)
                .putString(PREF_LAST_CONTEXT_TYPE, contextType)
                .apply();

        if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            releaseWakeLock();
        }
        setStatus("Starting Spotify…");
        stopButton.setEnabled(true);
        startNowPlayingPolling();

        new Thread(new Runnable() {
            public void run() {
                final String message = playSpotifyPlaylist(playlistId, contextType);
                runOnUiThread(new Runnable() {
                    public void run() { setStatus(message); }
                });
            }
        }).start();
    }

    private void handleSpotifyTransportStop() {
        setStatus("Stopping…");
        new Thread(new Runnable() {
            public void run() {
                final String message = spotifyStop();
                runOnUiThread(new Runnable() {
                    public void run() { setStatus(message); }
                });
            }
        }).start();
    }

    private String spotifyStop() {
        String token = getValidAccessToken();
        if (token == null) return "Spotify isn't connected.";
        try {
            String deviceId = getPreferredDeviceId(token);
            if (deviceId == null || deviceId.length() == 0) {
                return "Couldn't find the tablet's Spotify device - pick it again in Family Setup.";
            }
            String deviceParam = "?device_id=" + URLEncoder.encode(deviceId, "UTF-8");
            httpPutJson(SPOTIFY_API_BASE + "/me/player/pause" + deviceParam, token, null);
            return "Stopped";
        } catch (Exception e) {
            return "Couldn't reach Spotify - check the tablet is online.";
        }
    }

    private void handleSpotifyResume() {
        setStatus("Resuming…");
        stopButton.setEnabled(true);
        startNowPlayingPolling();
        new Thread(new Runnable() {
            public void run() {
                final String message = spotifyResume();
                runOnUiThread(new Runnable() {
                    public void run() { setStatus(message); }
                });
            }
        }).start();
    }

    private String spotifyResume() {
        String token = getValidAccessToken();
        if (token == null) return "Spotify isn't connected.";
        try {
            String deviceId = getPreferredDeviceId(token);
            if (deviceId == null || deviceId.length() == 0) {
                return "Couldn't find the tablet's Spotify device - pick it again in Family Setup.";
            }
            String deviceParam = "?device_id=" + URLEncoder.encode(deviceId, "UTF-8");
            HttpResult result = httpPutJson(SPOTIFY_API_BASE + "/me/player/play" + deviceParam, token, null);
            if (result.code == 200 || result.code == 204) return "Playing";
            handler.post(new Runnable() {
                public void run() { handleSpotifyTransportPlay(); }
            });
            return "Starting…";
        } catch (Exception e) {
            return "Couldn't reach Spotify - check the tablet is online.";
        }
    }

    private void handleSpotifyTransportSkip() {
        setStatus("Skipping…");
        new Thread(new Runnable() {
            public void run() {
                String message;
                String token = getValidAccessToken();
                if (token == null) {
                    message = "Spotify isn't connected.";
                } else {
                    try {
                        String deviceId = getPreferredDeviceId(token);
                        if (deviceId == null || deviceId.length() == 0) {
                            message = "Couldn't find the tablet's Spotify device - pick it again in Family Setup.";
                        } else {
                            httpPostNoBody(SPOTIFY_API_BASE + "/me/player/next?device_id="
                                    + URLEncoder.encode(deviceId, "UTF-8"), token);
                            message = "Playing";
                        }
                    } catch (Exception e) {
                        message = "Couldn't reach Spotify - check the tablet is online.";
                    }
                }
                final String finalMessage = message;
                runOnUiThread(new Runnable() {
                    public void run() { setStatus(finalMessage); }
                });
            }
        }).start();
    }

    private void handleSpotifyTransportBack() {
        setStatus("Going back…");
        new Thread(new Runnable() {
            public void run() {
                String message;
                String token = getValidAccessToken();
                if (token == null) {
                    message = "Spotify isn't connected.";
                } else {
                    try {
                        String deviceId = getPreferredDeviceId(token);
                        if (deviceId == null || deviceId.length() == 0) {
                            message = "Couldn't find the tablet's Spotify device - pick it again in Family Setup.";
                        } else {
                            httpPostNoBody(SPOTIFY_API_BASE + "/me/player/previous?device_id="
                                    + URLEncoder.encode(deviceId, "UTF-8"), token);
                            message = "Playing";
                        }
                    } catch (Exception e) {
                        message = "Couldn't reach Spotify - check the tablet is online.";
                    }
                }
                final String finalMessage = message;
                runOnUiThread(new Runnable() {
                    public void run() { setStatus(finalMessage); }
                });
            }
        }).start();
    }

    private void handleSpotifyBackLongPress() {
        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        final String contextId = prefs.getString(PREF_LAST_PLAYLIST_ID, "");
        final String contextType = prefs.getString(PREF_LAST_CONTEXT_TYPE, "playlist");
        if (contextId.length() == 0) {
            setStatus("Nothing has been played yet - choose the playlist or the book first.");
            return;
        }
        if (!"audiobook".equals(contextType)) {
            startSpotifyPlaylistPlayback(contextId, contextType);
            return;
        }
        setStatus("Going back to the start…");
        new Thread(new Runnable() {
            public void run() {
                final String message = restartSpotifyAudiobookFromBeginning(contextId);
                runOnUiThread(new Runnable() {
                    public void run() { setStatus(message); }
                });
            }
        }).start();
    }

    private String restartSpotifyAudiobookFromBeginning(String id) {
        String token = getValidAccessToken();
        if (token == null) return "Spotify isn't connected.";
        try {
            String deviceId = getPreferredDeviceId(token);
            if (deviceId == null || deviceId.length() == 0) {
                return "Couldn't find the tablet's Spotify device - pick it again in Family Setup.";
            }
            String deviceParam = "?device_id=" + URLEncoder.encode(deviceId, "UTF-8");
            String playUrl = SPOTIFY_API_BASE + "/me/player/play" + deviceParam;
            String body = "{\"context_uri\":\"spotify:show:" + id + "\",\"offset\":{\"position\":0},\"position_ms\":0}";
            HttpResult result = httpPutJson(playUrl, token, body);
            boolean started = (result.code == 200 || result.code == 204);
            if (!started) {
                String fallbackBody = "{\"context_uri\":\"spotify:audiobook:" + id
                        + "\",\"offset\":{\"position\":0},\"position_ms\":0}";
                HttpResult retry = httpPutJson(playUrl, token, fallbackBody);
                started = (retry.code == 200 || retry.code == 204);
            }
            if (!started) return "Couldn't go back to the start - check the tablet is online.";
            return "Playing";
        } catch (Exception e) {
            return "Couldn't reach Spotify - check the tablet is online.";
        }
    }

    private void startNowPlayingPolling() {
        if (nowPlayingPollingActive) return;
        nowPlayingPollingActive = true;
        handler.post(nowPlayingPollRunnable);
    }

    private void stopNowPlayingPolling() {
        nowPlayingPollingActive = false;
        handler.removeCallbacks(nowPlayingPollRunnable);
    }

    private void pollSpotifyNowPlaying() {
        new Thread(new Runnable() {
            public void run() {
                final String display = fetchSpotifyNowPlayingText();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (display != null && isSpotifyTheActiveSource()) {
                            nowPlayingText.setText(display);
                        }
                    }
                });
            }
        }).start();
    }

    private String fetchSpotifyNowPlayingText() {
        String token = getValidAccessToken();
        if (token == null) return null;
        try {
            HttpResult result = httpGet(SPOTIFY_API_BASE + "/me/player/currently-playing", token);
            if (result.code == 204 || result.body == null || result.body.length() == 0) {
                return "Nothing playing";
            }
            if (result.code != 200) return null;
            JSONObject json = new JSONObject(result.body);
            JSONObject item = json.optJSONObject("item");
            if (item == null) return "Nothing playing";
            String title = item.optString("name", "");
            if (title.length() == 0) return "Nothing playing";
            String subtitle = "";
            JSONArray artists = item.optJSONArray("artists");
            if (artists != null && artists.length() > 0) {
                subtitle = artists.getJSONObject(0).optString("name", "");
            } else {
                JSONObject show = item.optJSONObject("show");
                if (show != null) subtitle = show.optString("name", "");
            }
            return subtitle.length() > 0 ? (title + "\n" + subtitle) : title;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Runs on a background thread. Returns a short message to show the
     * person. contextType is "playlist" for the music playlist, or
     * "audiobook" for the audiobook slot. Playback tries Spotify's
     * unofficial-but-working "show"-style context first (native server-side
     * resume, see Round 12's history), falling back to the officially-typed
     * audiobook URI if that's rejected.
     */
    private String playSpotifyPlaylist(String playlistId, String contextType) {
        String token = getValidAccessToken();
        if (token == null) {
            return "Spotify isn't connected yet.";
        }
        try {
            String deviceId = getPreferredDeviceId(token);
            if (deviceId == null || deviceId.length() == 0) {
                wakeSpotifyApp();
                return "Starting Spotify… try the button again in a few seconds.";
            }
            String playUrl = SPOTIFY_API_BASE + "/me/player/play?device_id=" + URLEncoder.encode(deviceId, "UTF-8");
            boolean isAudiobook = "audiobook".equals(contextType);
            String primaryUri = isAudiobook ? "spotify:show:" + playlistId : "spotify:playlist:" + playlistId;
            String body = "{\"context_uri\":\"" + primaryUri + "\"}";
            HttpResult result = httpPutJson(playUrl, token, body);
            if (result.code == 200 || result.code == 204) {
                return "Playing";
            }
            if (result.code == 404) {
                return "That device isn't available right now - open Spotify on the "
                        + "tablet, then pick it again under \"Which Device Plays the Music?\" in Family Setup.";
            }
            if (isAudiobook) {
                String fallbackBody = "{\"context_uri\":\"spotify:audiobook:" + playlistId + "\"}";
                HttpResult retryResult = httpPutJson(playUrl, token, fallbackBody);
                if (retryResult.code == 200 || retryResult.code == 204) {
                    return "Playing";
                }
                return "Couldn't start the book (" + result.code + "/" + retryResult.code
                        + ") - check the tablet is online and try again.";
            }
            return "Couldn't start Spotify (" + result.code + ") - check the tablet is online.";
        } catch (Exception e) {
            return "Couldn't reach Spotify - check the tablet is online.";
        }
    }

    private String getPreferredDeviceId(String token) throws Exception {
        String deviceId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_SPOTIFY_DEVICE_ID, "");
        if (deviceId.length() == 0) {
            deviceId = findActiveOrFirstDevice(token);
        }
        return deviceId;
    }

    private String findActiveOrFirstDevice(String token) throws Exception {
        HttpResult result = httpGet(SPOTIFY_API_BASE + "/me/player/devices", token);
        if (result.code != 200) return null;
        JSONObject json = new JSONObject(result.body);
        JSONArray devices = json.optJSONArray("devices");
        if (devices == null || devices.length() == 0) return null;

        String firstId = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.getJSONObject(i);
            if (firstId == null) firstId = device.optString("id", null);
            if (device.optBoolean("is_active", false)) {
                return device.optString("id", null);
            }
        }
        return firstId;
    }

    /**
     * Round 36, 2026-08-23: now clears this window's immersive system-bar
     * flags immediately before launching Spotify - see
     * clearSystemBarsBeforeHandoff()'s doc comment for the full reasoning
     * (this is the "proven working" pattern Round 35 compared restartApp()
     * against, and it turned out to be blank too, which is what pointed the
     * diagnosis at hideSystemBars() rather than AlarmManager timing).
     */
    private void wakeSpotifyApp() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(SPOTIFY_PACKAGE);
            if (launchIntent != null) {
                clearSystemBarsBeforeHandoff();
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                scheduleReturnToKiosk();
            }
        } catch (Exception ignored) {
            // Spotify isn't installed, or lock task mode blocked it - nothing more we can do here.
        }
    }

    private void scheduleReturnToKiosk() {
        handler.postDelayed(new Runnable() {
            public void run() {
                try {
                    Intent selfIntent = new Intent(MainActivity.this, MainActivity.class);
                    selfIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(selfIntent);
                } catch (Exception ignored) {
                    // Nothing more to do if this fails.
                }
            }
        }, RETURN_TO_KIOSK_DELAY_MS);
    }

    private void pauseSpotifyBestEffort() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getString(PREF_SPOTIFY_ACCESS_TOKEN, "").length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String token = getValidAccessToken();
                    if (token != null) {
                        String deviceId = getPreferredDeviceId(token);
                        String deviceParam = (deviceId != null && deviceId.length() > 0)
                                ? "?device_id=" + URLEncoder.encode(deviceId, "UTF-8") : "";
                        httpPutJson(SPOTIFY_API_BASE + "/me/player/pause" + deviceParam, token, null);
                    }
                } catch (Exception ignored) {
                    // Best effort only - fine if nothing was playing on Spotify.
                }
            }
        }).start();
    }

    // ---------- Spotify: small HTTP helpers (no external library needed) ----------

    private HttpResult httpPostForm(String urlStr, String formBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = conn.getOutputStream();
            os.write(formBody.getBytes("UTF-8"));
            os.close();
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private HttpResult httpGet(String urlStr, String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private HttpResult httpPutJson(String urlStr, String accessToken, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.close();
            }
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private HttpResult httpPostNoBody(String urlStr, String accessToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private HttpResult readResponse(HttpURLConnection conn) throws IOException {
        HttpResult result = new HttpResult();
        result.code = conn.getResponseCode();
        InputStream stream = (result.code >= 200 && result.code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        result.body = readStream(stream);
        return result;
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    // ---------- Responsive button sizing (mirrors the web prototype) ----------

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientation(newConfig.orientation);
    }

    private void applyOrientation(int orientation) {
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        buttonsContainer.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        buttonsContainer.post(new Runnable() {
            public void run() { sizeButtons(); }
        });
    }

    private void sizeButtons() {
        int availW = buttonsContainer.getWidth();
        int availH = buttonsContainer.getHeight();
        if (availW <= 0 || availH <= 0) return;

        boolean row = buttonsContainer.getOrientation() == LinearLayout.HORIZONTAL;
        float maxPx = dp(380);
        float minPx = dp(110);

        float size = row
                ? Math.min(Math.min(availW / 3.3f, availH * 0.85f), maxPx)
                : Math.min(Math.min(availH / 3.3f, availW * 0.72f), maxPx);
        size = Math.max(size, minPx);

        int sizeInt = (int) size;
        for (Button b : new Button[]{playButton, stopButton}) {
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            lp.width = sizeInt;
            lp.height = sizeInt;
            b.setLayoutParams(lp);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.14f);
        }
        ViewGroup.LayoutParams nowPlayingLp = nowPlayingText.getLayoutParams();
        nowPlayingLp.width = sizeInt;
        nowPlayingLp.height = sizeInt;
        nowPlayingText.setLayoutParams(nowPlayingLp);
        nowPlayingText.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.085f);

        float secondarySize = Math.max(Math.min(size * 0.58f, dp(150)), dp(100));
        int secondarySizeInt = (int) secondarySize;
        for (Button b : new Button[]{backButton, skipButton}) {
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            lp.width = secondarySizeInt;
            lp.height = secondarySizeInt;
            b.setLayoutParams(lp);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, secondarySize * 0.15f);
        }

        float volumeSize = Math.max(Math.min(size * 0.65f, dp(190)), dp(104));
        int volumeSizeInt = (int) volumeSize;
        for (Button b : new Button[]{volumeDownButton, volumeUpButton}) {
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            lp.width = volumeSizeInt;
            lp.height = volumeSizeInt;
            b.setLayoutParams(lp);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, volumeSize * 0.16f);
        }

        // Build 42: the book and music buttons are now wide rounded
        // rectangles in their own bottom row (see contentButtonsRow in
        // activity_main.xml). Width comes from the layout's equal weights;
        // only the height and text size are set here. The height follows
        // the screen's height (about 13%, kept between 110dp and 170dp)
        // rather than the big buttons' size, because the big buttons are
        // themselves sized from whatever space this row leaves - basing one
        // on the other could make the two keep resizing each other.
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float rowHeight = Math.max(Math.min(screenH * 0.13f, dp(170)), dp(110));
        int rowHeightInt = (int) rowHeight;
        boolean rowHeightChanged = false;
        for (Button b : new Button[]{spotifyToggleButton, bookButton}) {
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp.height != rowHeightInt) {
                lp.height = rowHeightInt;
                b.setLayoutParams(lp);
                rowHeightChanged = true;
            }
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, rowHeight * 0.2f);
        }
        // If the row just changed height, the space left for the big buttons
        // changed too, so size them once more after the next layout pass.
        // The second pass finds the row already at this height, so it stops.
        if (rowHeightChanged) {
            buttonsContainer.post(new Runnable() {
                public void run() { sizeButtons(); }
            });
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
