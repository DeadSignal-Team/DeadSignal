# Dead Signal

The original Java Swing version of Dead Signal: a top-down stealth horror game where you find a keycard and escape a facility while avoiding a monster. Hallway connections change during play. You can hide in lockers and throw objects to distract the monster.

This repository starts with an unchanged snapshot of the saved `Main.java`. It contains the original 2D game; the separate jMonkeyEngine 3D prototype is not included.

## Requirements

- JDK 17 or newer, with `java` and `javac` available in your terminal.
- A desktop environment for the Java Swing game window.

VS Code with the Extension Pack for Java is optional.

## Build and run

Open a terminal in the repository root and run:

```shell
javac --release 17 -d build/classes Main.java
java -cp build/classes com.mycompany.deadsignal.Main
```

Choose **NEW GAME** from the menu to start. Compiled files stay in the ignored `build/` directory.

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

The imported source has `DEBUG = true`. In this mode, shifts happen every 30 seconds and the random generator uses a fixed seed. Normal mode uses a 2–3 minute shift interval. The initial import preserves these settings.

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

```shell
javac --release 17 -d build/checks Main.java tests/WarningCuesCheck.java
java -Djava.awt.headless=true -cp build/checks com.mycompany.deadsignal.WarningCuesCheck build/cue-checks
```

The checks cover proximity while undetected, wall/locker attenuation, awareness escalation, sting cooldown, mute and restart cleanup, settings pause, and clear playfield/HUD rendering. They also save preview images and an audio sample under the ignored `build/cue-checks/` directory.

## Movement input checks

These checks exercise the Swing key bindings and movement timing without opening a
game window. They cover held input, turning, running, walls, focus loss, lockers,
restart, and panel cleanup.

```shell
javac --release 17 -d build/test-classes Main.java tests/MovementInputTest.java
java "-Djava.awt.headless=true" -cp build/test-classes com.mycompany.deadsignal.MovementInputTest
```

## Sprite images

Sprite assets are not included in the initial repository. The game uses its built-in fallback shapes when an image cannot be loaded.

The source looks for player and monster images under `/assets/` on the classpath, `src/main/resources/assets/`, or `assets/` relative to the working directory. The filenames and letter casing are listed in `SpriteAssets` inside `Main.java`. Add the actual images in a later pull request if both collaborators want them tracked.

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
