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
| M, while debug mode is enabled | Reveal the map |

The imported source has `DEBUG = true`. In this mode, shifts happen every 30 seconds and the random generator uses a fixed seed. Normal mode uses a 2–3 minute shift interval. The initial import preserves these settings.

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
