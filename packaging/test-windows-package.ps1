param(
    [Parameter(Mandatory = $true)]
    [string]$AppImage,
    [Parameter(Mandatory = $true)]
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$AppImage = (Resolve-Path -LiteralPath $AppImage).Path
$gameJar = Join-Path $AppImage 'app\DeadSignal.jar'
$libraries = Join-Path $AppImage 'app\lib'
$runtimeJava = Join-Path $AppImage 'runtime\bin\java.exe'
foreach ($required in @($gameJar,$runtimeJava,(Join-Path $AppImage 'DeadSignal.exe'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing packaged file: $required" }
}
$info = Get-Content -Raw -LiteralPath (Join-Path $AppImage 'app\build-info.json') | ConvertFrom-Json
if ($info.debug -or $info.mainSourceSha256 -ne (Get-FileHash -LiteralPath (Join-Path $repo 'Main.java')).Hash.ToLowerInvariant()) {
    throw 'Package does not match the current normal-mode source.'
}

# Check every bundled asset byte-for-byte, and ensure no Java source was shipped.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($gameJar)
try {
    if ($archive.Entries | Where-Object { $_.FullName -like '*.java' }) { throw 'Release JAR contains source files.' }
    $assetRoot = Join-Path $repo 'assets'
    $assetCount = 0
    foreach ($asset in Get-ChildItem -LiteralPath $assetRoot -Recurse -File) {
        $relative = 'assets/' + $asset.FullName.Substring($assetRoot.Length + 1).Replace('\','/')
        $bundled = Join-Path (Join-Path $AppImage 'app') $relative
        if (-not (Test-Path -LiteralPath $bundled -PathType Leaf)) { throw "Missing bundled asset: $relative" }
        if ((Get-FileHash -LiteralPath $bundled).Hash -ne (Get-FileHash -LiteralPath $asset.FullName).Hash) {
            throw "Asset changed: $relative"
        }
        $assetCount++
    }
    Write-Output "Bundled assets verified: $assetCount; no Java source in release JAR."
} finally { $archive.Dispose() }

$checkRoot = Join-Path $repo ('build\package-checks\' + [guid]::NewGuid().ToString('N'))
$testClasses = Join-Path $checkRoot 'classes'
$emptyDirectory = Join-Path $checkRoot 'empty-working-directory'
New-Item -ItemType Directory -Path $testClasses,$emptyDirectory -Force | Out-Null
$testFiles = @(Get-ChildItem -LiteralPath (Join-Path $repo 'tests') -Filter '*.java' | ForEach-Object { $_.FullName })
$testFiles += Join-Path $PSScriptRoot 'ReleaseModeCheck.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 --module-path $libraries --add-modules javafx.swing,javafx.media -cp $gameJar -d $testClasses @testFiles
if ($LASTEXITCODE -ne 0) { throw 'Packaged-test compilation failed.' }
$javaOptions = @('--module-path', $libraries, '--add-modules', 'javafx.swing,javafx.media',
    '--enable-native-access=javafx.graphics,javafx.media')
$classPath = "$gameJar;$testClasses"
$savedPath = $env:PATH
$savedJavaHome = $env:JAVA_HOME
Push-Location $emptyDirectory
try {
    $env:PATH = Join-Path $env:SystemRoot 'System32'
    $env:JAVA_HOME = $null
    foreach ($test in @('ReleaseModeCheck','ArtAssetsCheck','FacilityGameplayTest','IntroVideoCheck',
            'MovementInputTest','RoomsOutroCheck','WarningCuesCheck','IntroMediaCheck','IntroWindowCheck','OutroWindowCheck')) {
        $options = @($javaOptions)
        if ($test -notin @('IntroMediaCheck','IntroWindowCheck','OutroWindowCheck')) { $options += '-Djava.awt.headless=true' }
        & $runtimeJava @options -cp $classPath "com.mycompany.deadsignal.$test" $checkRoot
        if ($LASTEXITCODE -ne 0) { throw "$test failed in the packaged runtime." }
    }
} finally {
    Pop-Location
    $env:PATH = $savedPath
    $env:JAVA_HOME = $savedJavaHome
}
Write-Output 'All packaged-runtime checks passed with JAVA_HOME cleared and no Java on PATH.'
