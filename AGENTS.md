# Filter Cam（空间滤镜相机）

PICO OS 6 spatial app (v0.2.0), Kotlin, PICO Spatial SDK 6.1.9 (BOM), package `io.embodit.filtercam`. Factory app
`filter-cam`. Design package (binding): `C:\Users\Admin\Downloads\spatial-app-factory\apps\filter-cam\design-package.json`
(states S00–S29, panels P1–P9, entities E0–E4, interactions T1–T18; gate B 2026-11-05: no fisheye). Built from Low Vision
Walk pieces (LensController, LensSpot, main window template, room Stage veil, PanelRig, LevelGate). All 2D UI is
SpatialUI + `PicoTheme` (`FcTheme`); no Material anywhere.

## Structure (v0.2.0, design round 4)

```
app/src/main/java/io/embodit/filtercam/
  Main.kt               camera window W1 (600 x 576 dp) + filter window W2 (280 x 576 dp, Placement.right(W1)) + RoomStage (Mixed)
  domain/               pure Kotlin, unit tested: Filters, Lut, FilterEngine (the one effect chain), Stamp, CamPerms (each
                        permission on its own, Spatial Data first), CameraWindow (PermCard, LensPolicy, SideWindow, AlbumNav,
                        ShotQueue, WindowLayout), RoomLooks, PanelPlace + SettleGate (P5), LevelGate, Geometry
  app/                  FcState + FcStore: the one state holder (windows, Stage and debug receiver share it)
  platform/             Renderer (viewfinder frames, previews, thumbnails, LUTs, samples), LensController (SpatialML read-back),
                        PhotoRepo (MediaStore), SoundBank, HelperActivity (photo picker, .cube picker, the two permissions one by one), Prefs
  ui/home/              HomeWindow (TabBar 拍照 / 相册 / 设置, settings with 叫回面前 + 退出应用), ShootPage (viewfinder + bottom bar:
                        thumbnail · shutter · 滤镜 + mute; permission card, toasts, fly-in), AlbumPage (newest first)
  ui/side/              FilterSide (W2: cards, strength, 「更多」 page S29 in place)
  ui/room/              RoomScreen (Stage + P5), RoomScene (ECS overlays, per-frame system), PanelRig, SceneKit
app/src/debug/          DebugCommandReceiver + DebugCommands (debug builds only)
tools/                  dev.sh, filters/, samples/, strings/strings.tsv + make_strings.py (edit the TSV, not strings.xml), sounds/, make_icon.py
```

## How a filter reaches the eye (v0.2.0)

- Camera (headset only, CAMERA + SPATIAL_DATA): SpatialML in **Readback Mode** (`ContainerType.DISABLED`, no container window).
  Each run copies the right-eye frame (`rectifiedVSTAccess`, 640 x 480 RGB) into one of two global tensors; run n+1 is
  submitted before frame n is read back (`LensController.pipelined`). The raw frame goes to `Renderer.renderLive` (3 workers,
  newest wins) → `FilterEngine.render` → the viewfinder in W1. The shutter takes the newest raw frame and renders it with
  the same engine, so the saved photo is the frame on screen. Logs: `LENS camera x Hz read-back`, `VIEWFINDER x Hz shown,
  render y ms`. Whether the session runs is one rule (`LensPolicy`): camera source + both permissions + a camera + W1 in
  front + not in room mode; album / settings pause it.
- Sample / my photo: `FilterEngine.render` at 640 for the viewfinder, 1024 for the saved photo. No SpatialML.
- Room mode: unchanged (Mixed Stage, camera-anchored quads).
- Emulator: never a camera session (S22). `readback --es mode ok` feeds the sample as fake camera frames at 20 Hz through
  the same path (log `READBACK FAKE`), `fail` makes the next camera shot fail (S08f).

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

## v0.1.2 notes (design round 3, QA #2)

- Camera page = preview 560 x 420 + ‹ name › + 更多 · 拍 · 静音 (B31: 7 controls with the 3 tabs; mute exempt). Everything else
  is in the 「更多」 drawer inside the same window; 「滤镜」 is no longer a tab (`MainWindowPage.inTabBar = false`).
- The viewfinder opens by itself after 「知道了」 and once per start (`FcStore.windowReady` / `autoLens`).
- Room mode uploads its 9 textures one by one on `Dispatchers.IO` (`RoomScene.uploadSet`): nine `TextureResource.create`
  in a row on the main thread was the QA #2 ANR. Keep texture uploads off the main thread.
- 「叫回面前」 opens a new instance of the `Home` window (new tag, system default placement) and closes the current one;
  `FcStore.recalling` keeps that close from counting as the user leaving.
- Side-by-side buttons are 16 dp apart, main buttons 64 dp high (ui_gates button-gap).
- CAMERA + SPATIAL_DATA, asked together (the SpatialML passthrough in Secure Mode needs none; the shutter's read-back
  needs CAMERA per the SDK docs and both per the 透写台 headset test). Asked when the camera source is picked (headset: at
  start); the preview and the viewfinder panel show 授权 / 去设置 / 再问一次 until granted, re-checked every 1.5 s.
- On the headset the system dialog backgrounds the main window: `domain/CameraFlow` keeps the viewfinder while our dialog
  is up and reopens it after the answer (now, or on ON_RESUME). Do not close the viewfinder on that ON_STOP.
- Never create the SpatialML session while that dialog is up (QA #4: the spot window has no focus then and the container
  lands over the main window for good): with a dialog coming, the viewfinder opens after the answer (`CameraFlow.openWithSource`). Emulator test: `pm revoke io.embodit.filtercam android.permission.CAMERA`,
  `source camera`, tap the system dialog (Don't allow ≈ 1340,783 / Allow ≈ 1592,783 on the 2560 frame).
- Source: https://github.com/kejing-dev/FilterCam (private).

## v0.1.5 notes: camera permissions one by one (profile D58)

- 2026-11-17 headset: CAMERA was granted, `com.picovr.permission.SPATIAL_DATA` was refused with USER_FIXED, and the card
  only said "camera access is off". Now `domain/CamPerms` checks CAMERA and SPATIAL_DATA each on its own
  (`HelperActivity.missing`), the card and the viewfinder text name the missing one(s) with a status line per permission,
  and only the missing ones are requested. A refusal with no rationale (including USER_FIXED, which the system refuses
  without a dialog) is "blocked": the card says where to find it in Settings and offers only 「去设置」.
- Settings names: "Spatial Data" in English (PermissionController `string/spatial_data`); a Chinese system says
  「空间数据」, so the Chinese text names both.
- 「去设置」 opens this app's App info page (one tap to Permissions). The permission list itself
  (`MANAGE_APP_PERMISSIONS`) needs GRANT_RUNTIME_PERMISSIONS, which apps cannot hold.
- Back from Settings (ON_RESUME, or the card's 1.5 s poll since Settings opens beside the window): once both are on and
  the camera was wanted, the viewfinder opens right there (`CameraFlow.onPermissionsComplete`; an already open
  viewfinder gets a new session). While a permission is missing the camera source stays picked, so the card stays.
- Emulator test of the headset state: `pm grant … CAMERA`, `pm set-permission-flags … SPATIAL_DATA user-set user-fixed`,
  then `source camera`. Card buttons: debug `permCard grant|retry|settings` (same calls as the buttons; adb taps do not
  reach the spatial windows on this ROM).

## v0.2.0 notes (design round 4, 2026-11-18 headset feedback)

- Cold start goes to the camera; a missing permission shows the permission card in the viewfinder (S00). 「开始授权」 asks
  Spatial Data, then Camera (two system dialogs, `HelperActivity`); 「去设置」 opens App info and the card re-checks every
  1 s, on resume and on focus. On the emulator the second dialog sits lower: Allow ≈ (1592, 896) for the first, (1592, 783)
  for the second on the 2560 frame (`uiautomator dump` to be sure).
- W2 opens with the first picture, folds on 相册 / 设置 / room / the permission card, comes back on 拍照. W1 「滤镜」 folds /
  unfolds it; its × folds it too (`SideState.closingByApp` tells the two apart). The fold is not kept across starts.
- After a shot: white 120 ms, frame held 300 ms, then it flies into the corner thumbnail (350 ms); saving shows a ring,
  up to 3 shots queue. Thumbnail / 相册 tab → the album on the newest photo (`album --es s dump` prints `isNewest`).
- Emulator screenshots: `pico-cli capture screenshot` sometimes returns a frame with the lower half black; retry, and if it
  stays, restart that emulator (`adb -s <serial> emu kill`, `pico-cli emulator start --avd <its avd>`).

## v0.2.1 notes (QA #6 q6-1, profile D60 补): BACK / controller B

- BACK and controller B never quit and never send the app to the background. They are caught at the earliest layer of
  every window (`platform/BackKeys.kt`, copied from GlowBench / SunmaoJoints): each stub Activity's Window.Callback, an
  unhandled-key listener on every root view of the process (swept on Activity events, tab taps, W2 open), and
  `Modifier.backKeys()` on the Compose roots of W1, W2 and P5; the OnBackPressedCallback is the last fallback.
- One handler, `FcStore.onBack` → `domain/BackPolicy`: W1 closed = open it again; W2 open = fold it, back to the
  viewfinder (拍照); room mode = stay (P5 「回取景台」 is the way out); otherwise eaten. Every press logs
  `BACK <layer> <key> -> <action>` under tag `FilterCam`.
- QA: `adb -s <dev> shell input keyevent BACK` / `BUTTON_B`, also after tapping a TabBar item.

## Debug broadcast (QA)

`adb -s <dev> shell am broadcast -a io.embodit.filtercam.DEBUG -p io.embodit.filtercam --es cmd <name> [--es s <arg>] [extras]`
(also `io.embodit.filtercam.DEBUG_LIVE`, registered at run time). Or `tools/dev.sh cmd <name> [arg] [extras]`. Every command
logs `CMD ok|fail <name>` under tag `FilterCam`; `state` logs `CMD reached <id>`; every state change logs `STATE <id>`.

| command | effect |
|---|---|
| `state --es id S00..S29` (also `S00a S00b S00c S01s S10h S12e`) | jump to a design state through the tap paths |
| `perm --es step intro\|ask1\|ask2\|missing\|blocked\|wait\|ok\|real` | permission card states without system dialogs (fake permissions; `real` = back to the real ones) |
| `permCard start\|settings\|sample\|ok` · `permReview` | the card's buttons (same calls) · settings 「重看权限说明」 |
| `filter --es id F01..F10\|LUT:<name>` · `hover --es id F02` / `hover off` | pick / look at a card |
| `source sample\|camera\|import [--es photo street\|desk\|window\|cat\|food\|night]` · `sample <name>` | picture source |
| `strength --ei n 0..100` · `stamp on\|off` · `stampFormat short\|dotted\|off` · `compare on\|off` · `timer on\|off` | look |
| `shutter` · `thumb` · `saveRetry` | shutter · tap the thumbnail · S26 「再试一次」 |
| `side show\|hide\|more\|less` | W2 open / fold / 「更多」 / back |
| `album open\|next\|prev\|delete\|confirm\|cancel\|use\|dump` | album page |
| `readback --es mode ok\|fail\|off` | fake camera frames on the emulator (`READBACK FAKE`) |
| `lens --es fail init\|timeout\|lost` · `lens --es pipe on\|off` · `lens retry` | S21 / S25 · pipelined read-back on / off · S21 「再试一次」 |
| `hold --es state countdown\|flash\|fly\|reading\|lost` · `release` | hold a transition under 3 s for a shot |
| `room` / `room off` · `roomFilter next\|prev` · `recenter` | room mode |
| `lang zh\|en` · `mute [on\|off]` · `volume --ei n 0..2` · `lut --es path <file>` · `seed --ei n 7` | settings |
| `save --es fail io` · `import --es fail bad` | S26 / S27 |
| `recall` · `quit [yes]` · `dump` | 叫回面前 · quit · state, read-back Hz, viewfinder Hz, permissions |

Screenshot list: every state in `apps/filter-cam/build/ui-audit.md` §8. States with the viewfinder or the room overlays:
shoot twice and keep the second (system-rendered layers). Transitions under 3 s: `hold` first.
