# Filter Cam（空间滤镜相机）

PICO OS 6 spatial app (v0.1.1), Kotlin, PICO Spatial SDK 6.1.9 (BOM), package `io.embodit.filtercam`. Factory app
`filter-cam`. Design package (binding): `C:\Users\Admin\Downloads\spatial-app-factory\apps\filter-cam\design-package.json`
(states S00–S28, panels P1–P9, entities E0–E4, interactions T1–T18; gate B 2026-11-05: no fisheye). Built from Low Vision
Walk pieces (LensController, LensSpot, main window template, room Stage veil, PanelRig, LevelGate). All 2D UI is
SpatialUI + `PicoTheme` (`FcTheme`); no Material anywhere.

## Structure

```
app/src/main/java/io/embodit/filtercam/
  Main.kt               main window P1 (680 x 680 dp) + LensSpot P3 (right, 320 x 280 dp) + RoomStage (Mixed)
  domain/               pure Kotlin, unit tested: Filters (8 v1 filters + F10 v1.1 card + imported LUTs), Lut (.cube 17/33/65
                        → 33³), FilterEngine (the one effect chain: LUT, lift/crush, sharpen, soft, blocks, halation, flash,
                        leak, vignette, scanlines, grain/noise, strength, instant frame, date stamp), Stamp (seven-segment),
                        CameraMasks (operator approximation for the live camera), RoomLooks (room overlays), PanelPlace +
                        SettleGate (P5, D9 / D29), LevelGate, Geometry
  app/                  FcState + FcStore: the one state holder (windows, Stage and debug receiver share it)
  platform/             Renderer (preview / thumbnails / LUTs / samples), LensController (SpatialML), PhotoRepo (MediaStore),
                        SoundBank, HelperActivity (photo picker, .cube picker, permissions), Prefs, trackers
  ui/home/              HomeWindow (TabBar pages, settings, cards over the page), ShootPage, FiltersPage, GalleryPage, LensSpot
  ui/shot/              ShotCardView (P4, drawn over the page inside P1 since v0.1.1)
  ui/room/              RoomScreen (Stage + P5), RoomScene (ECS overlays, per-frame system), PanelRig, SceneKit
app/src/debug/          DebugCommandReceiver + DebugCommands (debug builds only)
tools/                  dev.sh, filters/make_luts.py, filters/filter_sheet.py, samples/fetch_samples.py (CC0 photos, sources.json),
                        strings/strings.tsv + make_strings.py (edit the TSV, not strings.xml), sounds/make_sounds.py, make_icon.py
```

## How a filter reaches the eye

- Sample / my photo: `FilterEngine.render` on the app side → preview box, and the same 640 × 480 frame uploaded to the
  SpatialML photo tensor (pipeline = RGB → RGBA into the panel's dynamic texture, resubmitted every 250 ms). Saved photos
  use the same function at 1024 × 768, so the viewfinder shows what gets saved.
- Camera (headset only): `rectifiedVSTAccess` → `keep * src + greyW * grey + add` with `CameraMasks` (15 Hz). Shutter reads
  the raw frame back (`readbackContentSuspend`, CAMERA + SPATIAL_DATA) and runs the full engine on it; original saved too.
  The emulator never builds a camera pipeline (S22).
- Room mode: Mixed Stage, camera-anchored quads (veil TRANSPARENT, light ADD with 4 phases / 6 s, grain ADD 4 Hz, date
  ADD), P5 placed once the head is still for 0.5 s.

## Build, install, run

```bash
export JAVA_HOME="C:/Users/Admin/.pico/primer-cli/jdk/jdk-21.0.12.1+1" JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
./gradlew.bat testDebugUnitTest assembleDebug
DEV=emulator-5554 tools/dev.sh install
```
Stop the Gradle daemons (`./gradlew.bat --stop`) before starting an emulator on kj-alienware (RAM). Filter sheet:
`python tools/filters/filter_sheet.py prep` → `gradlew testDebugUnitTest` → `python tools/filters/filter_sheet.py sheet out.png`.

## v0.1.1 layout notes (QA #1)

- The emulator's default view is about +/-38 deg; P1 at 1.75 m already takes +/-17 deg. P4 lives inside P1; the SpatialML
  container is 0.44 x 0.33 m (`LensController.CONTAINER_W/H`, `assets/lens/lens_panel.glb` panel 0.432 x 0.324 m).
- `Placement` offsets must not be negative (they overflow, see feedback). The panel GLB must stay PBR: unlit draws black.
- After an install with the viewfinder open the system reopens P1 at the old LensSpot spot: quit (debug `quit yes`)
  with the lens closed, then launch from the launcher.
- `SpatialMLInstance.createSession` is a blocking binder call that hangs while an earlier session's destroy is stuck:
  never call it on the main thread (`LensController.createSession`: IO, waits for the destroy, 8 s timeout).
- Sample photos: `assets/samples/CREDITS.md` (CC0, Wikimedia Commons); re-fetch with `python tools/samples/fetch_samples.py`.

## Debug broadcast (QA)

`adb -s <dev> shell am broadcast -a io.embodit.filtercam.DEBUG -p io.embodit.filtercam --es cmd <name> [--es s <arg>] [extras]`
(also `io.embodit.filtercam.DEBUG_LIVE`, registered at run time). Or `tools/dev.sh cmd <name> [arg] [extras]`. Every command
logs `CMD ok|fail <name>` under tag `FilterCam`; `state` logs `CMD reached <id>`; every state change logs `STATE <id>`.

| command | effect |
|---|---|
| `state --es id S00..S28` (also `S07r`, `S08n`, `S08f`) | jump to a design state through the tap paths |
| `filter --es id F01..F10\|LUT:<name>` · `hover --es id F02` / `hover off` | choose / look at a card |
| `source sample\|camera\|import [--es photo street\|desk\|window\|cat\|food\|night]` · `sample <name>` | picture source |
| `strength --ei n 0..100` · `stamp on\|off` · `stampFormat short\|dotted\|off` · `compare on\|off` · `timer on\|off` | camera page |
| `shutter` | press the shutter (same path as the button) |
| `readback --es mode ok\|denied\|fail\|sessionLevel\|off` | fake camera read-back on the emulator (log `READBACK FAKE`) |
| `lens open\|close` · `lens --es fail init\|timeout\|lost` | viewfinder / S21 / S25 |
| `room` / `room off` · `roomFilter next\|prev` · `recenter` | room mode |
| `card again\|ok\|delete\|deleteYes\|deleteNo\|redo\|retry` · `gallery --ei i 0` | shot card / gallery |
| `lang zh\|en` · `mute [on\|off]` · `volume --ei n 0..2` · `guide reset\|ok` · `lut --es path <file>` | settings |
| `save --es fail io` · `import --es fail bad` | S26 / S27 |
| `seed --ei n 7` · `hold --es state countdown\|flash\|closing\|reopen\|reading` · `release` · `dump` · `quit` / `quit yes` | QA |

Screenshot list: every state in `apps/filter-cam/build/ui-audit.md` §8. States with the viewfinder or the room overlays:
shoot twice and keep the second (system-rendered layers). Transitions under 3 s: `hold` first.
