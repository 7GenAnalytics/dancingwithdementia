# Setting up a tablet: locked-down (kiosk) setup

This is the **locked-down** setup, for care homes or anyone likely to wander out of the app. It needs a computer and wipes the tablet. For a simpler setup with no computer, see [GETTING-STARTED.md](GETTING-STARTED.md); that guide also explains how to create the Spotify developer app you need in step 9 below.

**Do these steps in this order, on a freshly reset tablet.** The order matters. Android will only let this app become the tablet's "Device Owner" while there are **no accounts on the tablet**: no Google account, and no Spotify. Add a Google account first and the tablet has to be wiped and started again.

Device Owner is what makes the tablet a proper kiosk. It blocks the bottom bar (Home, Back, Recents), the pull-down status bar and the lock screen, while still allowing Spotify to run. Without it the app can only hide those bars, and a swipe from the screen edge brings them back.

You need:

- the tablet and a USB cable that carries data (some charging cables don't);
- a computer (Mac, Windows or Linux) with `adb` installed. `adb` comes in Google's free [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools);
- the app's APK file, downloaded to the computer from the [releases page](https://github.com/7GenAnalytics/DancingwithDementia-releases/releases);
- your Spotify developer app's **Client ID** ([GETTING-STARTED.md, step 1](GETTING-STARTED.md#1-create-your-own-spotify-developer-app-once)).

## 1. Wipe the tablet

1. If the tablet has been used before, remove its Google account first: Settings, Accounts (or Passwords & accounts), Google, Remove account. This stops the tablet asking for the old account after the reset.
2. Charge to at least 50%.
3. Settings, System, Reset options, **Erase all data (factory reset)**. Use the Settings menu, not the power button recovery menu.

## 2. First start: set up with nothing on it

Work through the welcome screens, but:

- **Connect to Wi-Fi.**
- **Skip** signing in to a Google account. Look for "Skip", "Set up offline" or "Not now".
- **Skip** setting a screen lock (PIN, pattern or password).
- Don't copy apps or data from another device.

## 3. Turn on USB debugging

1. Settings, About tablet, tap **Build number** 7 times until it says you are a developer.
2. Settings, System, Developer options, switch on **USB debugging**.
3. Plug the tablet into the computer and run:

```
adb devices
```

Tap **Allow** on the tablet (tick "Always allow from this computer"). Run `adb devices` again. You should see a serial number followed by `device`.

## 4. Install the app

Check the file is the one that was published, then install it. Replace the file name with the one you downloaded:

```
shasum -a 256 DancingwithDementia-44.apk          # Mac or Linux
certutil -hashfile DancingwithDementia-44.apk SHA256   # Windows
adb install DancingwithDementia-44.apk
```

Compare the checksum with the one published for that build on the releases page. The install should end with `Success`.

**Android 15 or newer:** if the install fails with a message about the app being built for an older version of Android, use this instead. Current builds need it until the next build raises the app's target version.

```
adb install --bypass-low-target-sdk-block DancingwithDementia-44.apk
```

## 5. Make the app the Device Owner

```
adb shell dpm set-device-owner com.seven7genanalytics.dancingwithdementia/.MusicKioskAdminReceiver
```

It should reply `Success: Device owner set to package ...`. Confirm with:

```
adb shell dpm list-owners
```

If it says the device owner **is already set**, that's fine: the tablet was set up before and nothing has changed. Carry on.

If it says there are **already some accounts on the device**, or that the device is **already provisioned**, something was added in step 2. Go back to step 1. Any other error: copy the exact message and open an issue on this repository, or email dancing@7genanalytics.com, before trying anything else.

## 6. Now add accounts and Spotify

Only after step 5 has succeeded:

1. Settings, Accounts, **add the Google account**.
2. Play Store, your profile icon, Manage apps & device, **Update all**. Also run Settings, Security, Google Play system update.
3. Install **Spotify** from the Play Store. Sign in with **Continue with email** and a password. Play any song once, so Spotify registers the tablet as a device.
4. Work through the settings checklist in step 7.

## 7. Tablet settings checklist

Menu names vary between makes and Android versions. If a setting isn't where this says, use the search bar at the top of Settings. Tick each one off; the reason is given so you can judge a tablet that differs.

### Name the tablet
- [ ] **Device name** (Settings, About tablet): give it a label such as `DwD-01`, and write the same on the back of the tablet. Spotify uses this name in "Which device plays the music?", so it stops the app controlling the wrong tablet.

### Screen and power
- [ ] **Screen lock: None.** Anything else puts a PIN or swipe between the person and the music.
- [ ] **Screen timeout (Sleep): longest option.** Short timeouts make the tablet look broken.
- [ ] **Stay awake** (Developer options), only if the tablet lives on its charger. Keeps the screen on while charging.
- [ ] **Adaptive brightness: off, brightness about 75%.** Auto-brightness dims the screen in a dark room, just when it's hardest to see.
- [ ] **Screen saver / Daydream: off.**
- [ ] **Auto-rotate:** off, locked to the way the tablet will stand (usually landscape). The app works either way, but a screen that flips when picked up is confusing.
- [ ] **Battery saver and Adaptive battery: off.** Low-cost tablets close background apps aggressively.
- [ ] **Battery use for Spotify and this app: Unrestricted** (Settings, Apps, the app, Battery). Without this, Android may close Spotify, and the buttons stop working.

### Sound
- [ ] **Media volume high**; ring, notification and alarm volume at zero.
- [ ] **Touch sounds, screen lock sound, charging sound, keyboard sounds and vibration: off.** Unexplained beeps are distressing.

### Notifications and interruptions
- [ ] **Do Not Disturb: on, with no end time.** Music still plays; everything else stays quiet and off screen.
- [ ] **Notifications off** for every app you can (Settings, Notifications, App notifications), especially Play Store, Google and Spotify.
- [ ] **Notifications on lock screen: none.**
- [ ] **Google Assistant / Gemini: off,** including "Hey Google", "Hold power button for Assistant" and any swipe-from-corner gesture. These are the usual way people end up in Google by accident.
- [ ] **Circle to Search / Google Discover: off** if the tablet has them.

### Network
- [ ] **Wi-Fi:** the network where the tablet will live, set to connect automatically.
- [ ] **Bluetooth, Location and Nearby Share: off,** unless a Bluetooth speaker is used.
- [ ] **Date and time: automatic,** in your local time zone. A wrong clock silently breaks the Spotify login.

### Spotify app settings
- [ ] **Signed in with this tablet's own account,** using "Continue with email". Keep a note of which account goes with which tablet.
- [ ] **Canvas: off** and **Audio quality: Normal.** Lighter on low-memory tablets.
- [ ] **Autoplay: decide per person.** On keeps music going after the playlist ends, but with songs they may not know; off stops at the end of the playlist.
- [ ] **Allow explicit content: off.**
- [ ] **Device broadcast status / Spotify Connect: on,** so the tablet shows in the app's device list.

### Updates
- [ ] **Play Store auto-update: over Wi-Fi only.** Keeps Spotify current.
- [ ] **System updates: download automatically over Wi-Fi.**

### Before it leaves your desk
- [ ] **Find My Device: on,** so a lost tablet can be located.
- [ ] **USB debugging and Wireless debugging: off.** Turn back on only for maintenance. Some tablets switch Developer options off by themselves, so expect to re-enable it.
- [ ] **Charger, cable and stand** go with the tablet.

## 8. Open the app

1. Make it the Home app. With the tablet still plugged in: `adb shell cmd package set-home-activity com.seven7genanalytics.dancingwithdementia/.MainActivity`, or Settings, Apps, Default apps, Home app, **Music Player** (the app's name on the tablet in current builds). From Build 44 the app also fixes this itself as Device Owner, so it can't be changed by mistake.
2. Long-press the faint gear icon (top right, about 1.5 seconds) to open the Tablet Menu, then **Family Setup**.
3. Check that an **Exit Kiosk Mode** button is shown. That means Device Owner is working.

Then do steps 9 and 10 in order. **The PIN is always the very last thing you set.** Set it earlier and you'll be typing it every time you go back into Family Setup during setup.

## 9. Connecting Spotify

About 15 minutes.

### Before you start
- The **Spotify Premium account** for this tablet (email and password). One account per tablet: an account can only play on one device at a time.
- Your **Spotify developer app's Client ID**. If you haven't made one yet, follow [GETTING-STARTED.md, step 1](GETTING-STARTED.md#1-create-your-own-spotify-developer-app-once). If this tablet's account is not the one that created the developer app, add it to the app's **User Management** list first (Spotify allows up to five accounts per developer app).
- The **playlist link(s)** and, if used, the **book link** for this person.

### Steps
1. **Family Setup, Exit Kiosk Mode.** This lets you switch between the Spotify app, the browser and this app while setting up.
2. If the tablet has anyone else's playlists, book or login on it (for example from a previous owner or a test), tap **Clear Spotify details** twice. The login, device, playlists and book are removed. The Client ID, name and PIN stay.
3. **Open the Spotify app** and check it is signed in with this tablet's account (Spotify, Settings shows the account). Play any song for a few seconds, then stop it. This registers the tablet with Spotify.
4. Back in **Family Setup**, in the **Spotify Playlist** section, paste your Client ID into **Spotify Client ID** and tap **Save Client ID**. If a code is already there, replace it with yours unless you know it is yours.
5. Tap **Get Login Link**. The browser opens Spotify's login page. Log in with **this tablet's account** and tap **Agree**.
   - You land on a plain page titled "Example Domain". That is expected.
   - Tap the address bar, select all, and **copy the whole address**.
   - Go back to Family Setup, paste it into **Paste code or web address here**, and tap **Submit Code**.
   - It should now say **Connected to Spotify.**
   - If the browser didn't open: long-press the blue link shown in Family Setup, copy it, and paste it into Chrome yourself.
6. Under **Which Device Plays the Music?**, tap **Refresh Devices List** and choose **this tablet** (the device name you set, e.g. DwD-01). If it isn't listed, play a song in the Spotify app again, then refresh.
7. **Playlist:** in the Spotify app, open the playlist, tap the three dots, **Share, Copy link**. Paste it into **Playlist link** and type a short button name (e.g. Hymns).
   - For more than one playlist, tick **More playlists** and fill in the second and third slots the same way.
8. **Audiobook (optional):** copy the book's link the same way, paste it into **Audiobook link**, and give it a name. Leave both boxes empty if there's no book; the Book button then doesn't appear.
9. Tap **Save Playlists**. It should say how many items were saved, or explain what's wrong with a link.

### If something goes wrong
- **Login says the user isn't registered / "Something went wrong":** this tablet's Spotify account isn't on your developer app's **User Management** list, or the Client ID belongs to someone else's developer app. Check both in the Spotify developer dashboard.
- **Buttons do nothing:** the wrong device is selected (step 6), or the Spotify app has been closed. Tablet Menu, **Restart App** usually sorts it.
- **Music plays on another device:** that account is signed in somewhere else too. Each tablet needs its own account.
- **"Spotify isn't connected yet." months later:** Spotify asks for a fresh login about every six months. Repeat step 5.

## 10. Finish: name, test, then PIN last

1. Type the person's name and tap **Save Name**. It shows at the top of the main screen.
2. Tap **Re-enter Kiosk Mode**, close Family Setup, and test every button on the main screen: Music (and each choice, if several), Book, Play, Stop, Back, Next, Louder, Quieter.
3. **Last of all:** Tablet Menu, Family Setup, **Set PIN**. Enter a 4-digit PIN twice. Write it down somewhere safe, and give it only to the family members or staff who need it.
4. Close Family Setup. The tablet is ready.

## Good to know

- **Getting to Android's own settings:** Tablet Menu, **Tablet Settings** (needs the PIN). This unlocks the kiosk and opens the tablet's normal home screen. From Build 44 the lock comes back on its own when the app is returned to. On older builds, use **Restart App** in the Tablet Menu, or restart the tablet.
- **Updates:** a new build signed with the same release key installs over the top (`adb install -r`, or from the releases page). Device Owner and Family Setup are kept.
- **Undoing Device Owner:** the only way is a factory reset. That is deliberate. It stops anyone switching the kiosk off by accident.
- **What the on-screen messages mean:** see the table in [GETTING-STARTED.md](GETTING-STARTED.md#what-the-messages-mean).
