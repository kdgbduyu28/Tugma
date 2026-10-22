# Changelog

Everything since the last pushed commit (`f3a3436`, phase 8). None of it has run on real hardware yet. It has been tested against the built-in demo household and the JVM test suite (168 tests).

## Headphones and Bluetooth

- **Redmi Buds (experimental, phase 9).** Ported from Gadgetbridge (AGPL-3.0 files):
  - framing and the challenge-response handshake;
  - battery, noise control and strength, EQ presets and a custom curve, toggles, Find earbuds.
  - Gesture remapping isn't ported.
- **Protocol lab (phase 10).** For devices Tugma doesn't support yet:
  - raw RFCOMM, BLE GATT and USB HID explorers;
  - `btsnoop_hci.log` import with a framing guess;
  - a guide to capturing traffic.
- **EQ Quick Settings tile (phase 15).**

## Sonos

- **Multiroom groups and group volume (phase 13).** Group volume and mute are sent to each group's coordinator; the sliders follow live events (phase 38).
- **Sound settings (phase 13):**
  - bass, treble, loudness;
  - night sound, speech enhancement;
  - sub, surround and height levels;
  - lip-sync delay and full-volume surrounds for music (phase 36).
- **Favorites, sleep timer and alarms (phase 14).**
- **Queue (phase 18):** view it, play from a track, remove tracks, clear it.
- **Inputs (phase 18):** line-in from any room, and TV on soundbars.
- **Snapshot restore covers multiroom groups too (phase 19).**
- **Speaker settings (phase 22):** status light, touch-control lock, Trueplay on/off, room rename.
- **Scenes (phase 23):** rooms, volumes and a favorite applied as one reviewed plan.
- **Scene reminders (phase 47):** a notification at the scene's time with an Apply button.
- **Pause all / Resume (phase 29).**
- **Now-playing line on room cards (phase 32).**
- **Live updates on the speaker screen (phase 35)** and on the room cards' now-playing lines (phase 37): UPnP event subscriptions (GENA), with polling as the fallback.
- **"Leave group" on the speaker screen (phase 34)**, for a room that's playing along with another room.

## Other brands

- **Linkplay (phase 11):** Arylic, WiiM, Audio Pro and other Linkplay speakers.
  - Playback, multiroom groups, and cross-model stereo pairs (experimental).
- **Linkplay presets, speaker EQ, rename and input switching (phases 17, 22, 27).** EQ and input switching are experimental.
- **Live updates for DLNA renderers (phase 40)** that declare event URLs.
- **Play a link on any brand (phase 21):** internet radio or audio files, with saved links.
- **Google Cast:**
  - Stop casting (phase 27).
  - Speakers added by IP now work for Linkplay and Cast too, not just Sonos (phase 26).

## Everyday use

- **Speaker remote in the notification shade (phase 16).** Volume buttons control the speaker through a remote MediaSession.
- **Volume buttons in the app (phase 28).** They control the speaker whose screen is open.
- **Volume mixer (phase 44):** every speaker, every brand, one screen.
- **Home-screen widget for one speaker (phase 20).**
- **Hide speakers from the list (phase 41).**
- **Speaker search (phase 45).**
- **Nicknames for DLNA and Cast speakers (phase 42).**
- **Sleep timer for DLNA, Linkplay and Cast speakers (phase 39)**, kept by the phone.
- **Appearance setting (phase 43):** light, dark or system theme, and wallpaper colours on Android 12+.
- **Backup and restore (phase 24)** of EQ profiles, saved links, scenes and speakers added by IP.

## Troubleshooting

- **First-run welcome (phase 48):** what Tugma is, plus Scan, Try demo or Add by IP.

- **Diagnostics (phase 25).** About → Run diagnostics, also linked from the Speakers tab (phase 33).
  - Checks the network, SSDP and each brand, with hints.
  - Builds a shareable report without request or response bodies.
- **"Copy as curl" on Debug log entries (phase 31).**
- **Tap a speaker's address to copy it (phase 46).**
- **Screen-reader labels on volume and sound sliders (phase 30).**

## CLI

New commands:
- **Sonos:** `groups`, `group`, `leave`, `group-volume`, `sound`, `favorites`, `play-favorite`, `queue`, `line-in`, `tv`, `sleep`, `alarms`, `alarm-add`, `alarm-delete`, `settings`, `play-url`.
- **Linkplay:** `lp-*` (including `lp-presets`, `lp-eq`, `lp-rename`, `lp-play-url`).
- **Google Cast:** `cast-play-url`.

Phase 12 (real-hardware fixes) is still waiting for hardware.
