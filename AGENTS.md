# Life-size Creatures (真实比例动物)

PICO OS 6 spatial app (v0.1.1), Kotlin, PICO Spatial SDK 6.1.9 (BOM), package `io.embodit.lifesize`. Factory app
`life-size-creatures`, skeleton B. Design package (binding):
`C:\Users\Admin\Downloads\spatial-app-factory\apps\life-size-creatures\design-package.json` (states S0–S13,
interactions I1–I15, panels, entities). Built from Spatial Ruler / Qianliyan pieces (panel pool, plane tracker,
head tracker, stage-root calibration, emulator input harness).

The field guide is a planar window in the Shared Space; picking an animal opens a secondary Mixed Stage (Full
Space) where the animal stands on the floor at true size with a floor tape, a "you" figure of your height, a
size tag, a dashed ceiling line when it is taller than the room, and an info card. All 2D UI is SpatialUI +
`PicoTheme` (`LifeTheme`); no Material anywhere (factory gate `bin/material-grep.txt`).

## Structure

```
app/src/main/java/io/embodit/lifesize/
  Main.kt                 DefaultWindowContainer (guide) + Stage("RoomStage") (room)
  domain/                 pure Kotlin: Species + Roster (8 animals, model specs, credits), ModelMetrics,
                          Room (state layers, I12 fit check, placement, footprint, "you" spot, 1:n move),
                          Copy (all zh/en copy; runtime language switch), Geometry (Vec3/Quat4/HeadPose)
  app/CreatureStore.kt    the one state holder of the process (window and Stage share it), hint priority
  platform/               LifeLog, PlaneTracker (FLOOR + lowest CEILING), HeadTracker, ControllerRay (ghost)
  ui/guide/GuideWindow.kt the guide window: top bar, 2 x 4 tiles (scale drawing), hint line; lends the
                          Stage navigator (openStage / closeStage) to the store
  ui/room/                RoomScreen (Stage composable: SpatialView, gestures, panel pool), RoomScene (ECS:
                          stage root, model cache, animals, floor catcher, "you", ghost, panels, per-frame
                          system), AnimalView (one animal), PanelRig (pool, ported), RoomPanels (size tag,
                          tape reading, you label, info card), SceneKit (materials, meshes)
app/src/debug/            DebugCommandReceiver + DebugCommands (debug builds only)
app/src/main/assets/models/*.glb    the 8 models (tools/prep_models.py output)
tools/                    dev.sh, qa_tour.sh, prep_models.py, make_icon.py, subset_cjk_font.py, harness/ (emulator input)
```

One direction of data: guide taps / Stage gestures / platform callbacks -> `CreatureStore` -> `CreatureState`
-> guide recomposes, `RoomScene.render` applies entities. Per-frame work (head pose, look turn, appear / fade,
drag, panel placement and facing, ghost) runs in `RoomFrameSystem` -> `RoomScene.onFrame`, never through Compose;
the head height reaches the store 5 times a second.

## Build, install, run (emulator-5562 only)

```bash
tools/dev.sh build          # inline JAVA_HOME = primer JDK 21
tools/dev.sh install        # installs on emulator-5562 (AVD PICO_6_1_Factory2) and starts the app
```
Never install on 5554 / 5556 / 5558 (other projects) or 5560 / 5564 (other factory apps).

## Debug broadcast (QA)

`adb -s emulator-5562 shell am broadcast -a io.embodit.lifesize.DEBUG -p io.embodit.lifesize --es cmd <name> [--es s <arg>]`,
or `tools/dev.sh cmd <name> [arg]`. Every command logs `CMD ok|fail <name>` under tag `LifeSize`.

| command | effect |
|---|---|
| `room S0|S13|S1|S2|S4|S8|S10` | jump to a room state (opens the Full Space when needed). S4 = horse 2.6 m ahead; S8 = shark + shiba; S13 sets freeze |
| `place <id>[,ahead[,side]]` | place an animal on the floor `ahead` m in front of the viewer (default 2.5), `side` m to the right |
| `tile <id>` / `exit` / `clear` / `retry` / `lang zh|en` | the guide's controls |
| `card <i>` / `close` / `rotate` / `sendback` / `scale fit|true` | the info card of the i-th animal (0/1) and its keys |
| `ceiling <m>` | pretend the net ceiling height is m metres |
| `height <m>` | the "you" height (until the next head sample changes it) |
| `slowload 1|0` | models take 6 s more (S12) |
| `freeze 1|0` | timed changes wait: the look-back after 3 s, the 3 s replacement hint, a debug drag's release |
| `drag <i>[,dx,dz,ms]` | drag the i-th animal (S9) |
| `ghost <ahead>` / `ghostoff` | show the placing ghost as if a controller ray hit the floor ahead (S3) |
| `stick <x>[,ms]` | hold the thumbstick at X = x (-1..1, right +) for ms (default 1500): turns the card's animal, else the one looked at (v0.1.1) |
| `dump` / `pose` / `panels` | `DUMP` line (state, hint, floor / ceiling, you), `ANIMAL` lines (center, n, fit, rendered box), panel slots |

Ids: `shiba wolf velociraptor stag horse shark triceratops trex`.

## QA screenshot list (design stateModel.qaCombos)

`tools/qa_tour.sh [state ...]` runs them all (two shots per state, the second is the one; output `.scratch/tour`):
S0, S13, S1, S2, S3 (ghost), S4 (horse), S5 (shiba card), S6 (T. rex: dashed ceiling line + "taller than your
room"), S6b (triceratops: taller + longer), S7 (T. rex 1:2 + badge + card segment), S8 (shark + shiba), S8x (third
animal replaces the first: hint "先放的…回图鉴了"), S9 (drag), S10, S11a/S11b (English guide + card), S12 (slowload).
Real input (gaze + pinch) works on the guide window, the floor and the animals: `tools/harness/emu.sh click X Y`
(see harness/TESTING.md). The harness maps 2160-px framebuffer coordinates; this AVD's console screenshots are
2560 px wide, so multiply screenshot pixels by 0.84375 before `click`.

## Runtime facts (emulator 6.1.0, 2026-10-21)

- **The guide window stays visible and takes pinches after `openStage(Mixed)`** (spike before any product code):
  no in-Stage fallback guide is needed. `closeStage` returns the Shared Space. The window's ON_DESTROY closes the Stage.
- The emulator room reports FLOOR at y = 0.40 and the lowest CEILING at 3.27 (net 2.87 m) within about 1 s of the
  Stage opening (sometimes ~7 s). The head stands at y = 1.65, so "you" reads 1.3 m on the emulator.
- **Model scale is measured, not trusted** (design: "按包围盒统一缩放"). `AnimalView.calibrate` reads the rendered box
  of the clone in the holder's parent space (the file's scene space) and scales it so that the measured length
  share (`ModelSpec.lengthRef`, tail root for mammals) equals the roster length. Animated files are measured
  after their idle clip has run 1.5 s: the T. rex file renders at 1 : 100 once animated (bind pose box ≈ 12 m,
  posed ≈ 0.12 m). Results are cached per species and written to `ModelMetrics` (fit check, footprint, tag).
- **The shark file stands upright in the engine** (its armature poses along +Y): `ModelSpec.fixPitchDeg = 90`
  turns it level, head forward. Re-check if the model is replaced.
- Room panels use an opaque slate, not `backgroundMaterial` (Stage attachment panels draw black bands with it
  on this emulator, Qianliyan QA q1-1 / q2-1). Every panel root has a fixed dp size, also when empty (an
  attachment panel without a size is laid out at a large default size).
- A pooled panel appears 2–5 s after it is shown at the emulator's idle 2–4 fps (the info card in particular):
  shoot twice.
- The controller tracking provider reports `DEVICE_NOT_SUPPORTED` in eye-hand mode; the ghost then stays hidden
  (design: no hover position in eye-hand mode). `ghost <ahead>` shows it for captures.
- **The guide window is drawn over Stage content.** The info card therefore takes the side (25 degrees from the
  line to the animal) away from where the window opened (straight ahead of the Stage origin) and keeps 24
  degrees clear of it. For an animal right of centre the card is on the right, outside the emulator's fixed
  forward view: QA places the S5 animal straight ahead (tour does) or turns the camera (Q / E keys).
- **Drag (I4)**: the animal slides by the hand's horizontal travel (emulator: cursor travel at the animal's depth)
  times distance / 1.5 m (1 - 4 x), staying on the floor outline. The Spatial Ruler pointing-line rule was tried
  first and threw a horse 6 m (grab point near eye height = a level line). Real pinch-drag verified:
  `tools/harness/emu.sh drag 1688 1097 1300 1097 -Steps 24 -StepMs 120 -HoldMs 2000 -EndHoldMs 1500` moved a horse 1.7 m.
- **Screenshots**: `tools/dev.sh shot` posts a focus-in first (60 fps for ~10 s); without it the console often
  returns an old frame. In the first ~20 s after a cold start the window glass sometimes renders its lower part
  black (the Qianliyan emulator flash): shoot S0 at least 25 s after start, take two shots, use the second.
- Real gaze + pinch verified on: a guide tile (S0 -> S13 -> S1 -> S3), the floor catcher (placing), an animal
  (card + turn), drag. Coordinates: screenshot px x 0.84375 = harness px.

## Thumbstick turn (v0.1.1, Swan device feedback "加个 joystick X rotate animal")

- Either controller's thumbstick X turns one animal about its vertical axis (through the middle of its length):
  the animal whose card is open, else the one nearest to where the viewer looks. Dead zone 0.15, quadratic ramp to
  60 degrees per second, the rate follows the stick with a 0.12 s time constant; right = clockwise from above.
  `RoomRules.stickTurnRate` (unit-tested), `RoomScene.stepStick`.
- Input: `ControllerTrackingProvider.addControllerActionListener` (SDK doc "Obtain controller input actions"),
  registered in `ControllerRay.start` and removed in `stop`. The callback only stores X; the frame system reads it.
- The turn lives in `AnimalView.stickYaw` while the stick is held (ECS only, no recomposition) and reaches the
  store once when it has come to rest (`CreatureStore.turnedBy`), like the drag. A drag of the same animal commits it.
- The guide shows a second hint line 「↔ 手柄摇杆左右：转动物」 while animals stand in the room.
- Emulator: in controller mode (`tools/harness/emu_mode.ps1 -Mode right -Avd PICO_6_1_Factory2 -Serial emulator-5562`,
  then a few `emu.sh move`) the provider turns `SUPPORTED` and the listener fires about 120 times a second (frame
  log `controller(support=... actions=... x=...)`), but the stick keys of the emulator's controller map (right stick
  L/J, left stick D/A) only react to the real keyboard: posted WM_KEYDOWN does not move them, and SendInput is not
  allowed on this shared desktop. Use `stick <x>` for captures; the real stick is checked on the headset.
- The emulator idles at 2-4 fps: the turn steps by wall-clock time (capped at 0.5 s a frame).

## App icon

Adaptive icon (`res/mipmap-anydpi/ic_launcher*.xml`): background `@color/ic_launcher_background` = jungle green
`#2E6B4F` (no other factory app uses it), foreground `res/drawable/ic_launcher_foreground.xml`, a vector T. rex traced
from the bundled model with a person at the same scale on the yellow tape. Regenerate with `python -I tools/make_icon.py
--factory <path to apps/life-size-creatures/icon.png>` (also writes the PICO 3D icon layers `icon_3d_*`). Verified in the
launcher (HOME key on the emulator): factory `apps/life-size-creatures/build/launcher.png`.

## Not built / device only

- Animal calls (I13): no CC0 call files bundled yet; the system cue `StateOn` plays and `SOUND call <id>` is
  logged (deviation). Real calls need CC0 audio files.
- True-scale feel, standing / sitting height estimate, hand-tracking drag feel, spatial-data permission prompt,
  the look of the models in a real room: real device only.
