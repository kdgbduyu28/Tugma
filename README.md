# Tugma

[![CI](https://github.com/kdgbduyu28/Tugma/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kdgbduyu28/Tugma/actions/workflows/ci.yml)

An unofficial Android controller that pairs speakers the official apps won't:

- **Stereo pairs of different Sonos models** (e.g. One + Era 100).
- **Any speaker as a soundbar's surrounds or sub** (fronts are experimental and hidden by default).
- **Sonos Favorites, sleep timer and alarms**: play anything in My Sonos, set or cancel a group sleep timer, and add, edit or delete the speakers' own alarms (chime or a radio favorite).
- **Quick Settings tile** to switch the phone EQ on and off.
- **Sleep timer for every brand**: Sonos uses the speaker's own timer. For DLNA, Linkplay and Cast, the phone sets an alarm that pauses the speaker (it may be a minute or two late while the phone dozes).
- **Nicknames** for DLNA and Google Cast speakers (they have no rename command), stored on the phone and included in backups.
- **Search** on the Speakers tab (shown once there are more than 8 speakers): matches name, model or address.
- **Hide speakers** you don't use (a TV's DLNA renderer, say) from the Speakers list, from their speaker screen. "Show hidden" brings them back.
- **Volume mixer**: every speaker's volume and mute on one screen, across brands (link at the bottom of the Speakers tab).
- **Home-screen widget** for any one speaker: what's playing, play/pause, next and volume.
- **Appearance** (About): system, light or dark theme, and wallpaper (Material You) colours on Android 12+.
- **Backup and restore** (About → Backup): EQ profiles, saved links, Sonos scenes, speakers added by IP and hidden speakers go into one JSON file. Restoring replaces only the sections the file contains.
- **Speaker settings**:
  - Sonos: status light (`Get/SetLEDState`), touch-control lock (`Get/SetButtonLockState`), Trueplay on/off (`Get/SetRoomCalibrationStatus`), and room rename (`SetZoneAttributes`, keeping the current icon and configuration).
  - Linkplay: rename (`setDeviceName:`).
  - CLI: `settings`, `lp-rename`.
- **Play a link on any brand**: save internet radio or audio links and play them on Sonos, DLNA, Linkplay or Google Cast.
  - Sonos turns `http` radio into `x-rincon-mp3radio:`. DLNA uses `SetAVTransportURI` with a titled DIDL. Linkplay uses `setPlayerCmd:play:`.
  - Cast launches the Default Media Receiver (`CC1AD845`) and `LOAD`s the link.
  - CLI: `play-url`, `cast-play-url`, `lp-play-url`.
- **Sonos queue and inputs**: see the group queue, play from any track, remove tracks or clear it (`Q:0`, `Seek TRACK_NR`); play any room's line-in (`x-rincon-stream:`) or switch a soundbar to TV (`x-sonos-htastream:<uuid>:spdif`). CLI: `queue`, `line-in`, `tv`.
- **Volume buttons in the app**: while a speaker's screen is open, the phone's volume buttons step that speaker's volume. If it doesn't report a volume, they change the phone's as usual.
- **Speaker remote in the notification shade**: any speaker's play/pause/next/previous on the lock screen, and the phone's volume buttons change that speaker's volume (remote MediaSession).
- **Input switching and stop casting**: Linkplay inputs (Wi-Fi, line-in, Bluetooth, optical, USB) via `setPlayerCmd:switchmode:` (experimental). Google Cast "Stop casting" sends receiver `STOP` with the app's session id.
- **Linkplay presets and speaker EQ**: play presets 1–10 (`MCUKeyShortClick`); on firmware with WiiM's API, preset names (`getPresetInfo`) and EQ on/off and presets (`EQGetStat`, `EQGetList`, `EQOn`/`EQOff`, `EQLoad`, experimental). CLI: `lp-presets`, `lp-eq`.
- **Snapshots restore groups too**: every write saves the topology first, and restoring brings back stereo pairs, home theaters and multiroom groups (leaves first, then joins).
- **Now playing on room cards**: each Sonos room shows what its group is playing.
  - Live from AVTransport events on each group coordinator where the speakers allow it.
  - Otherwise a 30 s poll using only `GetTransportInfo` and `GetPositionInfo`; it slows to 2 minutes while events flow.
- **Pause all / Resume** (Speakers tab): pauses every Sonos group that's playing; Resume restarts just those groups.
- **Scene reminders**: give a scene a time and days. At that time a notification offers to apply it; nothing changes until you tap Apply. Reminders survive reboots.
- **Sonos scenes**: save which rooms play together, their volumes and an optional favorite, then apply it in one tap. Applying builds one plan (leaves, joins, `SetVolume`, then the favorite) that you review before it's sent.
- **Sonos multiroom groups and sound settings**: group rooms (or every room), group volume and mute, plus bass, treble, loudness and the soundbar's night sound, speech enhancement, sub, surround and height levels.
- **Volume, mute and play/pause for any brand's Wi-Fi speaker** that exposes a standard UPnP/DLNA MediaRenderer, plus the same controls for Sonos.
- **Google Cast speakers, displays and groups** (Chromecast built-in, from any maker): volume, mute and playback. Groups and pairs made in Google Home are shown read-only.
- **Phone-side EQ for any headphone or speaker**: 10-band graphic EQ with preamp, presets, AutoEQ import, and a profile per output device that switches automatically.
- **Soundcore headphone settings (experimental)**, ported from [OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30): battery, noise cancelling / transparency, and the headphones' own EQ, for the models whose protocol is ported.
- **Linkplay speakers** (Arylic, WiiM, Audio Pro and other Linkplay-platform brands): volume/playback, multiroom groups across models, and experimental left/right stereo pairs.
- **Protocol lab** for devices Tugma doesn't know yet (SoundPEATS, Moondrop…): raw RFCOMM, BLE GATT and USB HID explorers, plus `btsnoop_hci.log` import with automatic framing analysis.
- **Redmi Buds settings (experimental)**, ported from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), including its challenge-response handshake: battery, noise control and strength, EQ presets and custom curve, toggles, and Find earbuds.

Tugma talks to speakers directly over the local network (UPnP/SOAP on port 1400). It needs no cloud account.

> **Unofficial.** Not affiliated with, endorsed by or supported by Sonos, Inc. or any other speaker maker. A firmware update could break it at any time. Use at your own risk.

## Modules

| Module | What it is |
|---|---|
| `:core` | Vendor-neutral models: brands, capabilities, `SpeakerSource`, `PlaybackControl`, `CommandPlan` (a bonding write must be `confirm()`ed before it can run), command log |
| `:upnp-core` | SSDP discovery, SOAP envelopes, the XML helper, OkHttp transport, a LAN-only guard, the DLNA renderer client (`dlna/`), fake renderers (`demo/`) |
| `:cast-core` | CASTV2 over TLS (hand-written protobuf frames, JSON namespaces): receiver volume/mute, media play/pause/next, `FakeCastHousehold` |
| `:eq-core` | EQ profiles, presets, per-device library, RBJ biquad maths, AutoEQ `ParametricEQ.txt` / `GraphicEQ.txt` import |
| `:bt-core` | Bluetooth RFCOMM transport interface; Soundcore protocol (`soundcore/`, with `FakeSoundcoreQ30`); Redmi Buds protocol and auth cipher (`redmibuds/`, AGPL-3.0, with `FakeRedmiBuds`); protocol-lab tools (`lab/`: hex, framing analyzer, btsnoop parser) |
| `:linkplay-core` | Linkplay `httpapi.asp` client (HTTP, or HTTPS for WiiM), multiroom group/kick/ungroup and channel planner, playback, `FakeLinkplayHousehold` |
| `:sonos-core` | Topology parser, bond roles, pair/home-theater planners and rules, snapshots and restore, `FakeSonosHousehold` |
| `:cli` | Desktop tool for checking every command against real speakers before using the app |
| `:app` | Jetpack Compose app (minSdk 26) |

The protocol modules are plain Kotlin/JVM with no Android dependencies.

## Download

Get the latest APK from [Releases](https://github.com/kdgbduyu28/Tugma/releases): open it on your phone and tap the `.apk`. It's a debug-signed build, so allow installs from your browser and, if Play Protect asks, tap **Install anyway**.

New releases are built by GitHub Actions when a version tag is pushed:

```bash
git tag v0.1.1 && git push origin v0.1.1
```

## Build

```bash
./gradlew test                 # 88 protocol, EQ and Bluetooth tests
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew :cli:installDist     # cli/build/install/tugma/bin/tugma
```

## Checking on real hardware (do this first)

On first launch a welcome screen offers **Scan my network**, **Try the demo household first** or **Add a speaker by IP**.

In **Debug log**, open any request and tap **Copy as curl** to replay it from a computer.

If discovery is blocked, add speakers by IP on the Speakers tab. Each address is tried as Sonos (port 1400), Linkplay (`getStatusEx`) and Google Cast (CASTV2 on 8009).

In the app: **About → Run diagnostics**. It checks Wi-Fi, runs a raw `ssdp:all` search, scans each brand with timings, and explains likely causes (multicast blocked, client isolation, a firewall on port 1400). **Share report** lists failed request URLs without any request or response bodies.

Run the CLI on a computer on the same Wi-Fi as the speakers.

1. **Read-only checks:**
   ```bash
   cli/build/install/tugma/bin/tugma discover
   cli/build/install/tugma/bin/tugma topology 192.168.1.20
   cli/build/install/tugma/bin/tugma actions 192.168.1.20    # AddBondedZones should be listed
   ```
2. **Dry runs.** These print the exact SOAP and send nothing:
   ```bash
   cli/build/install/tugma/bin/tugma pair 192.168.1.30 192.168.1.31 --dry-run
   cli/build/install/tugma/bin/tugma ht 192.168.1.20 --lr 192.168.1.22 --rr 192.168.1.23 --sw 192.168.1.21 --dry-run
   ```
3. **Real writes.** Drop `--dry-run`, then type `yes` at the prompt. Add `--verbose` to see every request and response.
4. **Undo:**
   ```bash
   cli/build/install/tugma/bin/tugma unpair 192.168.1.30
   cli/build/install/tugma/bin/tugma ht-remove 192.168.1.20 192.168.1.22
   ```
5. **Save fixtures.** `tugma topology-xml <ip> > sonos-core/src/test/resources/zgs_mine.xml` saves your real topology as a test fixture.

**Other brands (DLNA):**
```bash
cli/build/install/tugma/bin/tugma renderers
cli/build/install/tugma/bin/tugma renderer-status http://192.168.1.50:49152/description.xml
cli/build/install/tugma/bin/tugma renderer-volume http://192.168.1.50:49152/description.xml 15
```
`renderers` lists each device's description URL. A speaker that doesn't appear there has no standard renderer. It may only offer Cast, AirPlay or its own app protocol.

**Google Cast** (by IP; groups use their own port, which the app finds over mDNS):
```bash
cli/build/install/tugma/bin/tugma cast-status 192.168.1.40
cli/build/install/tugma/bin/tugma cast-volume 192.168.1.40 30
cli/build/install/tugma/bin/tugma cast-pause 192.168.1.40
```

`--demo` runs any command against the built-in fakes: Sonos at `192.168.50.11`–`19`, DLNA at `192.168.50.31`–`34`, Cast at `192.168.50.41`–`43` (plus a group at `192.168.50.41:32187`).

In the app, the **Debug** tab logs every request and response and can share them as text. **Snapshots** saves the topology before every change and suggests an undo.

## Protocol notes (checked against SoCo and pair-sonos)

| Action | SOAP call | Sent to |
|---|---|---|
| Stereo pair | `DeviceProperties#AddBondedZones`, `ChannelMapSet=L:LF,LF;R:RF,RF` | Left speaker |
| Fallback for old firmware | `CreateStereoPair` | Used only if the speaker's SCPD lacks `AddBondedZones` |
| Separate a pair | `RemoveBondedZones`, `ChannelMapSet=""`, `KeepGrouped=0` | Either speaker |
| Home theater | `AddHTSatellite`, arg **`ChannelMapSet`** = `BAR:LF,RF;A:LR;B:RR;C:SW` | Soundbar |
| Remove a satellite | `RemoveHTSatellite`, `SatRoomUUID=<uuid>` | Soundbar |

- `HTSatChanMapSet` is only the attribute name in ZoneGroupState. The `AddHTSatellite` argument is `ChannelMapSet`.
- The home-theater map always includes every satellite, old and new.
- **Multiroom groups:** joining sends `SetAVTransportURI` with `x-rincon:<coordinator UUID>` to each new member's AVTransport. Leaving sends `BecomeCoordinatorOfStandaloneGroup` (SoCo `join()` / `unjoin()`).
  - Only whole rooms can be grouped. A stereo pair's right half, satellites and Subs follow their room.
  - Group changes are shown and confirmed like bonds, and a snapshot is saved first.
- **Group volume:** `GroupRenderingControl` on the coordinator (`SnapshotGroupVolume`, then `SetGroupVolume`; `Get/SetGroupMute`). It applies immediately, like volume.
  - The Groups screen follows `GroupVolume`/`GroupMute` events, so changes made in other apps show up.
- **Sound settings:** `Get/SetBass`, `Get/SetTreble`, `Get/SetLoudness` and `Get/SetEQ` on RenderingControl.
  - EQ types are `NightMode`, `DialogLevel`, `SubEnable`, `SubGain`, `SurroundEnable`, `SurroundLevel`, `MusicSurroundLevel`, `HeightChannelLevel`, `SurroundMode` (full-volume surrounds for music) and `AudioDelay` (lip sync, 0–5), with SoCo's ranges.
  - The app only shows the settings the speaker answers. They apply immediately, like volume.
  - CLI: `groups`, `group`, `leave`, `group-volume`, `sound <ip> bass=2 night=on …`.
- **Favorites:** ContentDirectory `Browse` of `FV:2`; each item's `res` and `r:resMD` are what Sonos needs to play it.
  - Streams play with `SetAVTransportURI`.
  - Playlists and albums (`x-rincon-cpcontainer:` and similar) replace the queue: `RemoveAllTracksFromQueue`, `AddURIToQueue`, then `SetAVTransportURI x-rincon-queue:<uuid>#0` and `Play`.
- **Sleep timer:** `ConfigureSleepTimer` (`HH:MM:SS`, blank cancels) and `GetRemainingSleepTimerDuration` on the coordinator.
- **Alarms:** AlarmClock `ListAlarms` / `CreateAlarm` / `UpdateAlarm` / `DestroyAlarm`, with SoCo's argument names. Recurrence is `DAILY`, `WEEKDAYS`, `WEEKENDS`, `ONCE` or `ON_<days>` (0 = Sunday).
  - CLI: `favorites`, `play-favorite`, `sleep`, `alarms`, `alarm-add`, `alarm-delete`.
- **Live updates (UPnP GENA):** the speaker screen subscribes to AVTransport (on the coordinator) and RenderingControl (on the speaker). Generic DLNA renderers get live updates too when their description declares an `eventSubURL`.
  - It runs a small NOTIFY server on the phone's Wi-Fi address and renews before `TIMEOUT` runs out.
  - It applies `LastChange` (`TransportState`, `CurrentTrackMetaData`, Master `Volume`/`Mute`) immediately, and drops to a 30 s poll while events flow.
  - If subscribing or renewing fails, it goes back to the 10 s poll.
- **Volume and playback use standard UPnP AV**, the same for Sonos and DLNA:
  - `RenderingControl`: `GetVolume`, `SetVolume`, `GetMute`, `SetMute` (`InstanceID=0`, `Channel=Master`).
  - `AVTransport`: `GetTransportInfo`, `GetPositionInfo`, `Play`, `Pause`, `Stop`, `Next`, `Previous`.
  - On Sonos, volume goes to the speaker itself and transport goes to its group coordinator.
  - These controls don't need confirmation, because they can't change how speakers are bonded.
- **Google Cast:**
  - Connection: CASTV2 on TLS port 8009 (groups use their own port), framed as a 4-byte length plus a `CastMessage` protobuf.
  - Namespaces: `tp.connection` CONNECT, `tp.heartbeat` PING/PONG, `receiver` GET_STATUS/SET_VOLUME (level 0–1, muted), `media` GET_STATUS/PLAY/PAUSE/STOP/QUEUE_UPDATE on the casting app's `transportId`.
  - Certificates: Cast devices use certificates no public CA signs, so they aren't verified. Connections are therefore refused for anything but private-network addresses.
  - Limits: apps without the standard media namespace allow volume changes but not playback control. Google exposes no local API for creating groups or stereo pairs.
  - Discovery: the app browses mDNS `_googlecast._tcp` with `NsdManager` and reads TXT `id`/`fn`/`md`/`rs`.

## Headphones and EQ

The **Headphones** tab shows where audio is playing now, your paired Bluetooth audio devices (needs the Nearby devices permission), and the EQ.

**How the EQ works:**
- **Engine:** Android's `DynamicsProcessing` (Android 9+) or `Equalizer` (Android 8).
- **Where it attaches:** the global output mix where the phone allows it, plus every audio session a music app announces (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`).
- **Background running:** a foreground service (`specialUse`) keeps it running while switched on.
- **Clipping:** a limiter catches boosts that would clip.
- **Per-device profiles:** each output (a Bluetooth device, wired, USB or the phone speaker) can have its own profile, and it switches when the output changes.
- **Live preview:** unsaved edits play live.

**AutoEQ import:** pick a `ParametricEQ.txt` or `GraphicEQ.txt` from [AutoEQ](https://github.com/jaakkopasanen/AutoEq).
- Parametric filters (PK, LSC, HSC) are evaluated as RBJ biquads at 48 kHz and sampled onto 31 third-octave bands.
- Unsupported filter types are skipped and reported.

**Android limits Tugma can't work around:**
- **Codec switching** (SBC/AAC/LDAC…) is system-only (`BLUETOOTH_PRIVILEGED`).
  - Tugma lists the phone's supported codecs (Android 15+).
  - It shows the active codec only if Android announces it.
  - It links to Developer options → Bluetooth audio codec.
- **Global EQ:** some phones refuse effects on the global output mix. The EQ then only reaches apps that announce their audio sessions, and Tugma says so.
- **After a reboot:** the EQ restarts the next time you open Tugma, not on its own.

## Linkplay

Uses the documented `GET /httpapi.asp?command=…` API ([Arylic's reference](https://developer.arylic.com/httpapi/)) over plain HTTP. If that fails, it retries over HTTPS, which WiiM uses with self-signed certificates; that's accepted only for private-network hosts.

**How it works:**
- **Discovery:** the speakers are found through the SSDP renderer search, then probed with `getStatusEx`. Ones that also show up as DLNA renderers are listed only once, under Linkplay.
- **Playback:** `getPlayerStatus` for status (hex-encoded titles), plus `setPlayerCmd:vol:/mute:/pause/resume/stop/next/prev`.
- **Groups:** `ConnectMasterAp:JoinGroupMaster:eth<host-ip>:wifi0.0.0.0` is sent to the guest; `multiroom:getSlaveList`, `multiroom:SlaveKickout:<ip>` and `multiroom:Ungroup` go to the host. Every group change shows its exact URLs and needs confirming.
- **Stereo pairs across models (experimental):** join the right speaker as a guest, then send `setPlayerCmd:slave_channel:1` to the host and `multiroom:SlaveChannel:<guest-ip>:2`.
  - Channel values are 0 stereo, 1 left, 2 right.
  - The channel is documented only as a read-only field; these two commands come from user reports.

CLI: `lp-status`, `lp-pair`, `lp-group`, `lp-kick`, `lp-ungroup`, `lp-channel`. Demo speakers are at `192.168.50.51`–`53`.

## Protocol lab

In the app: **Headphones → Open protocol lab**.

**Explorers.** Raw writes are locked until you turn on "I understand".
- **RFCOMM:** pick a paired device and one of its advertised service UUIDs, then send hex. Replies are logged.
- **BLE GATT:** connect over LE, list services and characteristics, then read, write or subscribe.
- **USB:** for DSP dongles. List devices and interfaces, claim the HID/vendor interface, send on the OUT endpoint or as HID `SET_REPORT`, and read the IN endpoint or `GET_REPORT`.

**Capture file:** import Android's `btsnoop_hci.log`. Tugma:
- reassembles ACL fragments;
- extracts RFCOMM UIH payloads and ATT writes/notifications;
- groups them into streams;
- guesses the framing: common header, 16-bit length field, constant trailer, sum/XOR checksums.

**How to capture:** step-by-step instructions for Android HCI snoop logging, `adb bugreport`, and USB captures with Wireshark + USBPcap.

## Soundcore (experimental)

Ported from OpenSCQ30 (GPL-3.0). **Not yet tested on real Soundcore hardware.** Its packet tests use OpenSCQ30's byte vectors, several of them captured from real devices.

**How it works:**
- **Connection:** RFCOMM to Soundcore's vendor service (`0cf12d31-fac3-4553-bd80-d6832e7xxxxx`), falling back to SPP.
- **Packets:** `08 ee 00 00 00` (outbound) or `09 ff 00 00 01` (inbound), a 2-byte command, a little-endian u16 total length, the body, and an 8-bit sum checksum.
- **Model detection:** guessed from the service UUID or the Bluetooth name. You can override it on the settings screen.

**Ported by model:**

| Feature | Models |
|---|---|
| Battery (single/dual + charging) | Most models |
| Firmware + serial | All models |
| Noise cancelling / transparency (classic format) | Q20i, Life Q35, Life Q30, Vortex, Life Dot 2S, Liberty 2 Pro+, Life Dot 2 NC, Life Note 3, Liberty Air 2 Pro |
| Headphone EQ (8 bands, presets + custom) | Life Q35, Life Q30, Life 2 Neo, C50i (one channel); Vortex, Life Note 3, Life Note 3S (both earbuds) |
| Full state on connect | Life Q30 |

**Not ported yet:** newer models' own sound-mode format, HearID-based EQ, button remapping, and per-model state packets. Those models show battery and firmware; undecoded packets appear in the Debug log.

## Redmi Buds (experimental)

Ported from Gadgetbridge's `redmibuds` support. **Not yet tested on real earbuds.** Gadgetbridge has no unit tests for it, so:
- the auth cipher is tested against vectors generated by running Gadgetbridge's original `Authentication.java`;
- the rest is tested against a fake that runs the full handshake and refuses everything until the phone answers its challenge correctly.

**How it works:**
- **Connection:** RFCOMM to `0000fd2d-0000-1000-8000-00805f9b34fb`.
- **Messages:** `fe dc ba` · type · opcode · big-endian u16 length · [status, on responses] · sequence · payload · `ef`.
- **Handshake:**
  1. The phone sends a 16-byte challenge.
  2. The earbuds send their own.
  3. Each side answers with a SAFER+-style cipher.
  4. Then confirm, then the device info, run info and config requests.

**Ported by model:**

| Model | Ported |
|---|---|
| Redmi Buds 5 Pro, 6, 6 Pro | Battery, noise cancelling/transparency + strength, adaptive ANC, EQ presets + 10-band custom, ear detection, auto-answer, dual connection, adaptive sound |
| Redmi Buds 3 Pro | Battery, noise control + strength, EQ presets, ear detection, auto-answer, dual connection |
| Redmi Buds 6 Lite | Battery, noise control, custom EQ, auto-answer, Find earbuds |
| Redmi Buds 6 Active / 8 Active | Battery, EQ presets (8 Active: + custom), Find earbuds, toggles |
| Redmi Buds 4 Active | Battery, firmware |

**Not ported yet:** touch-gesture remapping. It's in Gadgetbridge and planned.

**Licence:** the files under `bt-core/.../redmibuds/` stay under **AGPL-3.0-or-later**, like Gadgetbridge's. They're combined with the rest of Tugma (GPL-3.0-or-later) under section 13 of both licences.

## Licence

GPL-3.0-or-later (see `LICENSE`).

Credits:
- Protocol formats were checked against [SoCo](https://github.com/SoCo/SoCo) (MIT) and [pair-sonos](https://github.com/erikmansson/pair-sonos) (MIT).
- Two topology fixtures come from SoCo's test suite.
- Soundcore support is ported from [OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30) by Kyle Scheuing and contributors (GPL-3.0), including its packet test vectors.
- Redmi Buds support is ported from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), originally by Jonathan Gobbo and Gadgetbridge contributors (AGPL-3.0-or-later; those files keep that licence).
