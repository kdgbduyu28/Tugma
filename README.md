# Tugma

An unofficial Android controller that pairs speakers the official apps won't:

- **Stereo pairs of different Sonos models** (e.g. One + Era 100).
- **Any speaker as a soundbar's surrounds or sub** (fronts are experimental and hidden by default).
- **Volume, mute and play/pause for any brand's Wi-Fi speaker** that exposes a standard UPnP/DLNA MediaRenderer, plus the same controls for Sonos.
- **Google Cast speakers, displays and groups** (Chromecast built-in, from any maker): volume, mute and playback. Groups and pairs made in Google Home are shown read-only.
- **Phone-side EQ for any headphone or speaker**: 10-band graphic EQ with preamp, presets, AutoEQ import, and a profile per output device that switches automatically.
- **Soundcore headphone settings (experimental)**, ported from [OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30): battery, noise cancelling / transparency, and the headphones' own EQ, for the models whose protocol is ported.

Tugma talks to speakers directly over the local network (UPnP/SOAP on port 1400). It needs no cloud account.

> **Unofficial.** Not affiliated with, endorsed by or supported by Sonos, Inc. or any other speaker maker. A firmware update could break it at any time. Use at your own risk.

## Modules

| Module | What it is |
|---|---|
| `:core` | Vendor-neutral models: brands, capabilities, `SpeakerSource`, `PlaybackControl`, `CommandPlan` (a bonding write must be `confirm()`ed before it can run), command log |
| `:upnp-core` | SSDP discovery, SOAP envelopes, the XML helper, OkHttp transport, a LAN-only guard, the DLNA renderer client (`dlna/`), fake renderers (`demo/`) |
| `:cast-core` | CASTV2 over TLS (hand-written protobuf frames, JSON namespaces): receiver volume/mute, media play/pause/next, `FakeCastHousehold` |
| `:eq-core` | EQ profiles, presets, per-device library, RBJ biquad maths, AutoEQ `ParametricEQ.txt` / `GraphicEQ.txt` import |
| `:bt-core` | Bluetooth RFCOMM transport interface and the Soundcore protocol: packet framing and checksum, model table, parsers, device session, `FakeSoundcoreQ30` |
| `:sonos-core` | Topology parser, bond roles, pair/home-theater planners and rules, snapshots and restore, `FakeSonosHousehold` |
| `:cli` | Desktop tool for checking every command against real speakers before using the app |
| `:app` | Jetpack Compose app (minSdk 26) |

The protocol modules are plain Kotlin/JVM with no Android dependencies.

## Build

```bash
./gradlew test                 # 80 protocol, EQ and Bluetooth tests
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew :cli:installDist     # cli/build/install/tugma/bin/tugma
```

## Checking on real hardware (do this first)

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

## Licence

GPL-3.0-or-later (see `LICENSE`).

Credits:
- Protocol formats were checked against [SoCo](https://github.com/SoCo/SoCo) (MIT) and [pair-sonos](https://github.com/erikmansson/pair-sonos) (MIT).
- Two topology fixtures come from SoCo's test suite.
- Soundcore support is ported from [OpenSCQ30](https://github.com/Oppzippy/OpenSCQ30) by Kyle Scheuing and contributors (GPL-3.0), including its packet test vectors.
