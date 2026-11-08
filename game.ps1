param(
    [ValidateSet('Run', 'Build', 'Test', 'MediaTest')]
    [string]$Action = 'Run',
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $JavaHome) {
    $compiler = Get-Command javac -ErrorAction SilentlyContinue
    if (-not $compiler) {
        # The Java extension includes a JDK even when Java is not on PATH.
        $extensionRoot = Join-Path $env:USERPROFILE '.vscode\extensions'
        $bundledCompiler = Get-ChildItem -LiteralPath $extensionRoot -Directory -Filter 'redhat.java-*' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | ForEach-Object {
                $runtimeRoot = Join-Path $_.FullName 'jre'
                Get-ChildItem -LiteralPath $runtimeRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                    $candidate = Join-Path $_.FullName 'bin\javac.exe'
                    if (Test-Path -LiteralPath $candidate) { $candidate }
                }
            } | Select-Object -First 1
        if (-not $bundledCompiler) {
            throw 'Install JDK 17 or newer and set JAVA_HOME, or pass -JavaHome with your JDK folder.'
        }
        $JavaHome = Split-Path (Split-Path $bundledCompiler -Parent) -Parent
    } else {
        $JavaHome = Split-Path (Split-Path $compiler.Source -Parent) -Parent
    }
}
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javac)) { throw "No Java compiler at $javac" }
if (-not [Environment]::Is64BitOperatingSystem -or $env:OS -ne 'Windows_NT') {
    throw 'This launcher downloads JavaFX for Windows x64. Other platforms need their matching JavaFX SDK.'
}

$javafxVersion = '17.0.21'
$dependencies = Join-Path $PSScriptRoot ".deps\javafx-$javafxVersion-win"
$hashes = [ordered]@{
    base = '4faa6d4f2a3bbc7649596eedc7d2db179cec3fa876de30f12005442fcba12adf'
    graphics = '126f5f80ff6f64eb81fcb97e1594351e81ca836eaa0e4942ca10e44579adef22'
    media = '9ae722f0b7908e06ca6335b84b586111af542306ec394d2b976cedad812068f1'
    swing = '20172604cceb4ae76a294284efc4ff76411ba5ea5be5d98964b6bf89e093c087'
}
New-Item -ItemType Directory -Path $dependencies -Force | Out-Null
foreach ($module in $hashes.Keys) {
    $artifact = "javafx-$module"
    $filename = "$artifact-$javafxVersion-win.jar"
    $destination = Join-Path $dependencies $filename
    if (-not (Test-Path -LiteralPath $destination)) {
        Write-Host "Downloading $artifact $javafxVersion..."
        Invoke-WebRequest -UseBasicParsing -Uri "https://repo.maven.apache.org/maven2/org/openjfx/$artifact/$javafxVersion/$filename" -OutFile $destination
    }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hashes[$module]) {
        throw "JavaFX checksum mismatch: $destination"
    }
}

$modules = @('--module-path', $dependencies, '--add-modules', 'javafx.swing,javafx.media')
$runtime = @($modules) + '--enable-native-access=javafx.graphics,javafx.media'
$classes = Join-Path $PSScriptRoot 'build\classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java' | ForEach-Object { $_.FullName })
& $javac --release 17 @modules -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Game compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets') -Destination $classes -Recurse -Force

if ($Action -eq 'Build') {
    Write-Host 'Game build passed.'
} elseif ($Action -eq 'Run') {
    & $java @runtime -cp $classes com.mycompany.deadsignal.Main
    if ($LASTEXITCODE -ne 0) { throw 'Game exited with an error.' }
} else {
    $testClasses = Join-Path $PSScriptRoot 'build\test-classes'
    New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
    $tests = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -Filter '*.java')
    $testSources = @($tests | ForEach-Object { $_.FullName })
    & $javac --release 17 @modules -cp $classes -d $testClasses @testSources
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    $classPath = "$testClasses$([IO.Path]::PathSeparator)$classes"
    foreach ($test in $tests) {
        $nativeMedia = $test.BaseName -in @('IntroMediaCheck', 'IntroWindowCheck', 'OutroWindowCheck')
        if (($Action -eq 'MediaTest') -ne $nativeMedia) { continue }
        $testOptions = @()
        if (-not $nativeMedia) { $testOptions += '-Djava.awt.headless=true' }
        & $java @runtime @testOptions -cp $classPath "com.mycompany.deadsignal.$($test.BaseName)" (Join-Path $PSScriptRoot 'build\cue-checks')
        if ($LASTEXITCODE -ne 0) { throw "$($test.BaseName) failed." }
    }
}
