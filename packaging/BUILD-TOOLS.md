# Windows build tools

The 0.1.0 installer uses these official portable build tools. They are developer
dependencies and are not installed globally by the packaging script.

| Tool | Official download | SHA-256 |
| --- | --- | --- |
| Eclipse Temurin JDK 25.0.5+8, Windows x64 | [JDK ZIP](https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8.zip) | `636bdc13dc649ddc7d5921c5c7d23cca6e44fd19d662b45cc9c02135a6c96c42` |
| WiX 3.14.1 portable binaries | [WiX ZIP](https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip) | `6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31` |

The JDK checksum comes from Adoptium's release metadata. The WiX checksum records
the official GitHub release ZIP used for this build. Extract each ZIP, then pass
the JDK directory containing `bin/jpackage.exe` as `-JavaHome` and the WiX
directory containing `candle.exe` and `light.exe` as `-WixHome`.

JavaFX 17.0.21 is fetched and checksum-verified by `game.ps1`. The installed game
uses a reduced OpenJDK runtime and the four JavaFX Windows JARs. Runtime component
notices are bundled; the JavaFX notices are copied from the official OpenJFX
`jfx17` source branch.

The native launcher uses a relative `$APPDIR/lib` module path. The game JAR's
manifest includes `Class-Path: .`, allowing artwork and videos in `app/assets/`
to load independently of the current working directory. MP4s remain regular
files rather than compressed JAR entries.

`test-windows-package.ps1` verifies all 30 assets against the checkout, checks
normal-mode behavior, and runs the existing gameplay and native cutscene tests
against the packaged JAR and runtime with no system Java on PATH.

Reference: [Oracle jpackage guide](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-overview.html).
