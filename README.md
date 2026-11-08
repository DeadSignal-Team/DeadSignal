# Dead Signal

The original Java Swing version of Dead Signal: a top-down stealth horror game where you find a keycard and escape a facility while avoiding a monster. Hallway connections change during play. You can hide in lockers and throw objects to distract the monster.

This repository starts with an unchanged snapshot of the saved `Main.java`. It contains the original 2D game; the separate jMonkeyEngine 3D prototype is not included.

## Requirements

- JDK 17 or newer, available through `JAVA_HOME`, your terminal, or the launcher's `-JavaHome` option.
- A desktop environment for the Java Swing game window.
- The included PowerShell launcher supports Windows x64 and downloads JavaFX 17.0.21 from Maven Central on its first run. It verifies the pinned SHA-256 hashes and caches the libraries in the ignored `.deps/` folder. Later builds and launches work offline.

VS Code with the Extension Pack for Java is optional.

For VS Code **Run and Debug**, open this worktree's folder and select **Dead Signal (intro worktree)**. The included profile builds the bundled video and launches with the JavaFX modules. A different worktree will run that worktree's game; it will not pick up this branch's uncommitted intro changes. The launcher can also detect the JDK bundled with the VS Code Java extension when neither `JAVA_HOME` nor `javac` is configured.

## Build and run

Open a terminal in the repository root and run:

```powershell
.\game.ps1
```

If your JDK is not on the terminal path, supply its installation folder:

```powershell
.\game.ps1 -JavaHome 'C:\path\to\jdk'
```

Use `-Action Build` to compile without launching. Compiled files and the bundled assets stay in the ignored `build/` directory. The launcher uses its own project directory, so it can also be invoked from another folder. JavaFX's `javafx.swing` and `javafx.media` modules are now required; the old plain `javac Main.java` command is insufficient.

## New Game intro

Choose **NEW GAME** to play `assets/video/intro.mp4` in the same window. **SKIP** is available immediately at the bottom right and uses the main menu button's font, weight, and responsive text size. The video keeps its original aspect ratio, with black bars where needed.

Gameplay starts after the video finishes or is skipped, so monster updates and warning audio do not run underneath the intro. Every new game from the menu gets the intro; **R** and direct gameplay restarts never replay it. Missing media, playback errors, or a video that cannot start within 15 seconds fall through to gameplay. Video/audio resources are released before the transition.

When replacing a current run, the existing New Game confirmation appears first. Canceling keeps that run, and **CONTINUE** resumes it directly without replaying the intro.

The supplied video is bundled with the build and does not depend on a personal Videos folder. JavaFX supports MP4/H.264 video with AAC audio; replacing the clip requires a compatible encoding.

## Successful escape outro

Reaching the keycard exit with the keycard plays `assets/video/outro.mp4` once for
that successful run. The monster, player input, shift timers, and warning audio
stop while it plays. Finishing the video or pressing the bottom-right **SKIP**
button returns to the main menu. Continue is disabled for a finished run.
Ordinary room departures, a locked exit, defeat, and R/restart do not play the
outro. Every later successful run plays it again. Missing or failed playback
falls back to the main menu, using the same cleanup and timeout as the intro.

## Controls

| Control | Action |
| --- | --- |
| WASD / arrow keys | Move |
| Shift + movement | Run |
| E | Pick up an object |
| Q | Throw the held object |
| F | Hide in or leave a locker |
| R | Restart |
| P | Warning volume and reduced-effects settings (pauses gameplay) |
| Space | Open/close the adjacent door you face |
| M | Open/close the remembered map |
| Esc | Close the map, or open/close the pause menu |

Hold a direction to keep moving. The most recently pressed direction takes priority;
release it to resume another direction you are still holding. Shift changes between
walking and running while moving. Switching away from the window clears held input.

Public playtest builds use `DEBUG = false`: debug information is hidden, each run uses fresh randomness, and shifts happen every 2–3 minutes. Developer debug mode uses a fixed seed and a 30-second interval.

## Rooms and doors

Every room has a persistent number and keeps its items, lockers, objectives, and door states when it changes location. Each shift exchanges all rooms except the player's current room. The monster travels with its room when applicable, and hallway routes can also change. Room contents are not randomly regenerated or shuffled: existing items retain their identity and local room positions. A loose item stranded by a changed hallway is moved to nearby valid floor, without creating or deleting items.

If a shift becomes due while the player is in a hallway, one shift waits until the player enters a room. That newly entered room is protected, and the next interval starts after the queued shift executes.

Visible doorframes have thicker outlines: green for open, amber for closed. Closed doors block movement and sight and muffle noise. Close an unoccupied doorway while the monster is immediately beside it to stun it for **2 seconds**. The monster has an **8-second stun cooldown** and can force a closed ordinary door open after a **2.5-second windup**. The keycard-locked exit remains a separate objective.

## Map and Continue

The map opens for at most **10 seconds**, and its **25-second cooldown starts when opened**. Closing early does not reset that cooldown. While it is open, player movement, item/locker/door actions, restart, and settings are disabled. **The monster and room-shift clock keep running.** M or Esc closes the map; getting caught closes it automatically.

The map records explored rooms and last-observed connections. Unexplored rooms are hidden, the monster is never drawn, and opening the map does not rescan the facility. Shifts can make its information outdated; revisiting rooms refreshes local observations. It replaces the previous M debug-reveal shortcut.

Esc opens a pause menu with Resume, Return to Main Menu, and Exit Game. Returning to the main menu preserves the same run for **Continue**. Gameplay, door/stun timers, shifts, and map cooldown freeze while paused or in the menu. Continue is available for a live run while the application remains open. Exiting the application loses that in-memory run; starting New Game asks before replacing it.

To check room exchanges, item conservation, doors, map behavior, and pause/Continue:

```shell
javac --release 17 -d build/test-classes Main.java tests/FacilityGameplayTest.java
java "-Djava.awt.headless=true" -cp build/test-classes com.mycompany.deadsignal.FacilityGameplayTest build/facility-previews
```

## Warning cues

Warning audio is generated in Java: an industrial drone, radio hiss, heartbeat, and escalation sting. No audio downloads or extra libraries are required.

- A quiet, nondirectional proximity layer plays when the monster is nearby, even if it has not detected you. Walls and hiding muffle this layer.
- The existing awareness meter controls the tension layers, heartbeat intensity, and red playfield-edge cues. The center and HUD remain clear. Warning feedback does not create noise events that attract the monster.
- Open **SETTINGS** from the main menu or press **P** during play. Set warning volume to **0** to mute audio. **Reduced visual effects** makes the edge cue gentler and disables its pulse and the awareness bar's pulse. Settings are kept for the current session.
- Gameplay pauses while the in-game settings dialog is open. Audio fades out when the game loses focus and after victory or defeat. Restart clears the previous warning state.
- If the system audio device cannot be opened, the game continues with visual cues.

To run the cue regression checks without opening an audio device or game window:

```powershell
.\game.ps1 -Action Test
```

The checks cover proximity while undetected, wall/locker attenuation, awareness escalation, sting cooldown, mute and restart cleanup, settings pause, and clear playfield/HUD rendering. They also save preview images and an audio sample under the ignored `build/cue-checks/` directory.

## Movement input checks

These checks exercise the Swing key bindings and movement timing without opening a
game window. They cover held input, turning, running, walls, focus loss, lockers,
restart, and panel cleanup.

```powershell
.\game.ps1 -Action Test
```

This also runs the intro flow checks: Skip, natural end, loading failures, duplicate callbacks, cleanup, R/restart behavior, and matching the menu font and bottom-right position across window sizes.

For a muted native playback check of the bundled MP4 (no video window is shown):

```powershell
.\game.ps1 -Action MediaTest
```

The native checks require a desktop session and verify decoding, playback completion, release, and replay. They also exercise the real New Game button, embedded video, Skip, R, replacement confirmation, and Continue in an off-screen game window. Add `-JavaHome` to either test command if needed.

## Sprite images

The twelve approved player sprites are bundled under `assets/player/`.
Each of `front`, `back`, `left`, and `right` has `player_idle_<direction>.png`
and `player_walk_<direction>_01.png` / `_02.png`.
White clothing stays opaque; the background is transparent. The PNGs retain
their original 64x64 canvases and colors. All twelve share a common render crop,
so changing direction keeps the same scale and foot baseline. Left and right
use the supplied direction-specific artwork. Existing movement timing, hiding
transparency, and missing-image fallback behavior are retained.

The six supplied monster frames are included as transparent PNGs under `assets/monster/`:
`monster_walk_right_01.png` through `monster_walk_right_03.png`, and
`monster_walk_left_01.png` through `monster_walk_left_03.png`.
They retain their original 64x64 canvases and frame order. Pixel-based removal
clears the white background, limb gaps, facial white patches, and pale JPEG edge
fringes. Retained pixels keep their original colors. The Downloads originals remain unchanged.

Successful horizontal steps choose the corresponding direction. Vertical movement
keeps the last side facing; standing still or being stunned holds the first frame.
All six frames share the same crop and foot baseline. Missing sprites still use
the game's fallback shapes.

All nine supplied room backgrounds are now loaded: `assets/rooms/room_01.png`
for Room 1 and `ROOM-2.png` through `ROOM-9.png` for Rooms 2-9. Filenames and
artwork are preserved. Each image follows its persistent room identity during
shifts and stays hidden beneath covered ceilings. `assets/rooms/hallway.png`
fills open hallway passages and rotates for vertical corridors. Artwork is
clipped to visible floor and drawn beneath actors, items, and doorframes.
Furniture and painted details remain decorative; collision is unchanged.

`game.ps1 -Action Test` includes room-art visibility, shifting, hallway clipping,
and successful-escape/outro lifecycle checks. `-Action MediaTest` verifies both
bundled clips and the real intro and outro window flows.

The source looks for player and monster images under `/assets/` on the classpath, `src/main/resources/assets/`, or `assets/` relative to the working directory. The filenames and letter casing are listed in `SpriteAssets` inside `Main.java`.

## Working together

Repository: `https://github.com/DeadSignal-Team/DeadSignal`

The repository is private. Accept your organization invitation and sign into GitHub with your own account before cloning. Organization owner access is shared between `yuzorai` and `Tetsu-07` once the invitation is accepted.

```shell
git clone https://github.com/DeadSignal-Team/DeadSignal.git
cd DeadSignal
git switch main
git pull --ff-only
git switch -c feature/short-description
```

Make a focused change, save the file, then run the build command above. Check which files you are committing:

```shell
git diff
git status
git add Main.java
git commit -m "Describe the change"
git push -u origin feature/short-description
```

Add any other intended source or documentation files explicitly. Open a pull request into `main` on GitHub, describe the change and how it was checked, and ask the other developer to review it. Merge after agreeing that it is ready, then update your local `main` before starting another branch.

The first commit is the agreed baseline directly on `main`. Later changes use branches and pull requests. Reviews are an agreed team workflow on GitHub Free; this private repository does not enforce required approvals with branch protection.

Each developer should use their own Git author identity and commit email. Never share authentication tokens or commit compiler output or local machine credentials.

## Windows installer

Public playtest version: **0.1.0**. The Windows x64 installer bundles the game,
all artwork and videos, JavaFX 17.0.21, and a private Java runtime. Players do not
need Java, Git, VS Code, PowerShell commands, or a first-run dependency download.
The installer offers a destination folder and creates desktop and Start Menu
shortcuts. It installs for the current user and supports uninstalling through
Windows Settings > Apps. Run progress remains in memory only.

Build on Windows x64 with a **full JDK 25** (tested with Eclipse Temurin
25.0.5+8) and WiX (tested with the portable WiX 3.14.1 binaries). These tools are
needed by the developer building the installer, not by players. The Java runtime
bundled with the VS Code extension lacks jpackage and jlink.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\package-windows.ps1 -JavaHome 'C:\path\to\full-jdk-25' -WixHome 'C:\path\to\wix'
```

The build refuses debug mode and uses a fresh staging directory to avoid stale
classes. Before creating the installer, it verifies every asset and runs the
packaged gameplay and native video checks from an empty working directory with
JAVA_HOME cleared and Java removed from PATH. It writes `build/releases/DeadSignal-0.1.0-windows-x64-setup.exe` and its
`.sha256` checksum. Use `-Version 0.1.1` for a later build and `-OutputDirectory`
for a different destination. Existing release files are not overwritten. For
app-image testing without WiX, use `-Type app-image`; this produces a folder
containing `DeadSignal.exe`, `app/`, and `runtime/`.

Before publishing, install the EXE into a clean directory and test that installed
copy outside the source folder. Verify bundled art, New Game, video playback and
Skip, movement, doors, the map, escape/outro, and uninstall. Run the existing
`game.ps1 -Action Test` and `-Action MediaTest` checks with the selected JDK.

Upload only the installer and checksum to the public
[DeadSignal-Releases](https://github.com/DeadSignal-Team/DeadSignal-Releases)
repository. Keep this source repository private. Mark 0.1.0 as a prerelease and
link directly to its installer; GitHub's automatic source archives are not the
game download. Release builds include `app/build-info.json` with their version,
Java/JavaFX versions, and the SHA-256 of Main.java.
