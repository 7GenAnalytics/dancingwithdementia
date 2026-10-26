# Dancing with Dementia

A simple app for those who struggle to access music or a book.

A simple, large-button Android music and audiobook player for older people and people living with dementia. One screen, three big buttons (Play, Now Playing, Stop), with everything else tucked behind a PIN-protected Family Setup.

Contact: dancing@7genanalytics.com

> **Setting up a tablet?** Start with [docs/TABLET-SETUP.md](docs/TABLET-SETUP.md) and follow it in order. The app must be made Device Owner *before* any Google account is added, or the tablet has to be wiped again.

## What it does

- Plays a Spotify playlist and a Spotify audiobook (or a folder of music files on the tablet). An optional "More playlists" tick box in Family Setup lets the Music button offer up to three named playlists
- Big, high-contrast buttons sized to the screen
- Runs as the tablet's home screen, so it comes back on its own after a restart or power cut
- Family Setup and Tablet Settings sit behind a 4-digit PIN

## Repository layout

| Path | What it is |
| --- | --- |
| `AndroidManifest.xml` | App ID, permissions, components |
| `src/` | Java source |
| `res/` | Layouts, button styles, strings |
| `res/values/config_defaults.xml` | Pre-filled Family Setup values, **committed empty** |
| `docs/TABLET-SETUP.md` | Setting up a new tablet, including Device Owner |
| `docs/BUILD.md` | How the app is built and signed |

## Private configuration

Deployment-specific values (Spotify Client ID, default playlist and book) never go in this repository. Put them in `local/res/values/config_defaults.xml`, which is git-ignored; the build overlays it on the empty committed file. Without it, the app simply starts unconfigured and Family Setup is used instead.

## Releases

Finished, signed APKs are published at [DancingwithDementia-releases](https://github.com/7GenAnalytics/DancingwithDementia-releases). Signing keys are never stored in either repository.
