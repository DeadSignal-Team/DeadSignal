param(
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version = '0.1.0',
    [ValidateSet('exe', 'app-image')]
    [string]$Type = 'exe',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$WixHome,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'build\releases')
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) {
    throw 'Build this installer on Windows x64.'
}
if (-not $JavaHome) {
    $compiler = Get-Command javac -ErrorAction SilentlyContinue
    if ($compiler) { $JavaHome = Split-Path (Split-Path $compiler.Source -Parent) -Parent }
}
foreach ($tool in @('javac', 'jar', 'jlink', 'jpackage')) {
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\$tool.exe"))) {
        throw 'Supply -JavaHome with a full JDK 25 (including jpackage and jlink). The VS Code bundled Java runtime is insufficient.'
    }
}
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$jlink = Join-Path $JavaHome 'bin\jlink.exe'
$jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
$jdkVersion = & $jpackage --version
if ($LASTEXITCODE -ne 0 -or $jdkVersion -notmatch '^25\.') {
    throw 'This packaging script is tested with a full JDK 25. Use -JavaHome to select it.'
}
if (Select-String -LiteralPath (Join-Path $PSScriptRoot 'Main.java') -Pattern 'static final boolean DEBUG = true;' -Quiet) {
    throw 'Public builds require DEBUG = false in Main.java.'
}

function Invoke-BuildTool([string]$Executable, [string[]]$ToolArguments) {
    & $Executable @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $Executable (exit $LASTEXITCODE)" }
}

$previousPath = $env:PATH
try {
    if ($WixHome) {
        if (-not (Test-Path -LiteralPath $WixHome -PathType Container)) { throw 'WixHome is not a directory.' }
        $env:PATH = "$WixHome;$env:PATH"
    }
    if ($Type -eq 'exe' -and -not (Get-Command wix -ErrorAction SilentlyContinue) -and
            -not ((Get-Command candle -ErrorAction SilentlyContinue) -and (Get-Command light -ErrorAction SilentlyContinue))) {
        throw 'WiX is required for an EXE installer. Supply -WixHome with the portable WiX 3.14.1 tools, or install WiX.'
    }

    # The development launcher fetches and checksum-verifies the pinned JavaFX JARs.
    & (Join-Path $PSScriptRoot 'game.ps1') -Action Build -JavaHome $JavaHome
    $javafx = Join-Path $PSScriptRoot '.deps\javafx-17.0.21-win'
    $stage = Join-Path $PSScriptRoot ('build\windows-installer\' + [guid]::NewGuid().ToString('N'))
    $classes = Join-Path $stage 'classes'
    $stagingInput = Join-Path $stage 'input'
    $libraries = Join-Path $stagingInput 'lib'
    $runtimeImage = Join-Path $stage 'runtime'
    $imageDirectory = Join-Path $stage 'image'
    New-Item -ItemType Directory -Path $classes,$libraries -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object { $_.FullName })
    Invoke-BuildTool $javac (@('--release', '17', '--module-path', $javafx, '--add-modules', 'javafx.swing,javafx.media', '-d', $classes) + $sources)
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets') -Destination $stagingInput -Recurse
    $manifest = Join-Path $stage 'MANIFEST.MF'
    @('Manifest-Version: 1.0', 'Class-Path: .', '') | Set-Content -LiteralPath $manifest -Encoding ASCII
    Invoke-BuildTool $jar @('--create', '--file', (Join-Path $stagingInput 'DeadSignal.jar'), '--main-class', 'com.mycompany.deadsignal.Main', '--manifest', $manifest, '-C', $classes, '.')
    Get-ChildItem -LiteralPath $javafx -Filter '*.jar' | Copy-Item -Destination $libraries
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'packaging\PLAYER-README.txt'),
        (Join-Path $PSScriptRoot 'packaging\THIRD-PARTY-NOTICES.txt'),
        (Join-Path $PSScriptRoot 'packaging\JAVAFX-LICENSE.txt') -Destination $stagingInput
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'packaging\javafx-legal') -Destination $stagingInput -Recurse
    Invoke-BuildTool $jlink @('--add-modules', 'java.desktop,java.logging,java.xml,jdk.unsupported,jdk.unsupported.desktop',
        '--strip-debug', '--no-header-files', '--no-man-pages', '--output', $runtimeImage)

    $sourceHash = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'Main.java')).Hash.ToLowerInvariant()
    [ordered]@{
        version = $Version
        platform = 'windows-x64'
        debug = $false
        mainSourceSha256 = $sourceHash
        java = $jdkVersion.Trim()
        javafx = '17.0.21'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stagingInput 'build-info.json') -Encoding UTF8

    Invoke-BuildTool $jpackage @('--type', 'app-image', '--name', 'DeadSignal', '--app-version', $Version,
        '--vendor', 'DeadSignal Team', '--description', 'Dead Signal: The Shifting Facility - public playtest',
        '--input', $stagingInput, '--main-jar', 'DeadSignal.jar', '--main-class', 'com.mycompany.deadsignal.Main',
        '--runtime-image', $runtimeImage, '--dest', $imageDirectory,
        '--java-options', '--module-path=$APPDIR/lib',
        '--java-options', '--add-modules=javafx.swing,javafx.media',
        '--java-options', '--enable-native-access=javafx.graphics,javafx.media')
    $applicationImage = Join-Path $imageDirectory 'DeadSignal'
    Invoke-BuildTool 'powershell.exe' @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $PSScriptRoot 'packaging\test-windows-package.ps1'), '-AppImage', $applicationImage, '-JavaHome', $JavaHome)
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    if ($Type -eq 'app-image') {
        $destination = Join-Path $OutputDirectory "DeadSignal-$Version-windows-x64"
        if (Test-Path -LiteralPath $destination) { throw "Output already exists: $destination. Choose another -OutputDirectory." }
        Copy-Item -LiteralPath $applicationImage -Destination $destination -Recurse
        Write-Output "Application image: $destination"
    } else {
        $destination = Join-Path $OutputDirectory "DeadSignal-$Version-windows-x64-setup.exe"
        if (Test-Path -LiteralPath $destination) { throw "Output already exists: $destination. Choose another -OutputDirectory." }
        $installerDirectory = Join-Path $stage 'installer'
        Invoke-BuildTool $jpackage @('--type', 'exe', '--name', 'DeadSignal', '--app-version', $Version,
            '--vendor', 'DeadSignal Team', '--description', 'Dead Signal: The Shifting Facility - public playtest',
            '--app-image', $applicationImage, '--dest', $installerDirectory,
            '--about-url', 'https://github.com/DeadSignal-Team/DeadSignal-Releases',
            '--win-per-user-install', '--win-dir-chooser', '--install-dir', 'DeadSignal',
            '--win-shortcut', '--win-menu', '--win-menu-group', 'DeadSignal',
            '--win-upgrade-uuid', 'e1791b68-427a-4a6d-9483-24c821eab071')
        $installers = @(Get-ChildItem -LiteralPath $installerDirectory -Filter '*.exe')
        if ($installers.Count -ne 1) { throw 'Expected exactly one installer.' }
        Copy-Item -LiteralPath $installers[0].FullName -Destination $destination
        $checksum = (Get-FileHash -LiteralPath $destination).Hash.ToLowerInvariant()
        "$checksum  $([IO.Path]::GetFileName($destination))" | Set-Content -LiteralPath "$destination.sha256" -Encoding ASCII
        Write-Output "Installer: $destination"
        Write-Output "SHA-256: $checksum"
        Write-Output "Application image for verification: $applicationImage"
    }
} finally {
    $env:PATH = $previousPath
}
