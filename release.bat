@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

set "DRY_RUN=0"
if /I "%~1"=="--dry-run" set "DRY_RUN=1"
if /I "%~1"=="-n" set "DRY_RUN=1"

set "GRADLE_FILE=app\build.gradle.kts"
if not exist "%GRADLE_FILE%" (
    echo [ERROR] %GRADLE_FILE% not found.
    exit /b 1
)

set "VER_TMP=%TEMP%\intactfolio_ver_%RANDOM%.txt"
powershell -NoProfile -Command "$q=[char]34; $text=[IO.File]::ReadAllText('%GRADLE_FILE%'); $cm=[regex]::Match($text,'versionCode\s*=\s*(\d+)'); $nm=[regex]::Match($text,'versionName\s*=\s*'+$q+'(\d+)\.(\d+)\.(\d+)'+$q); if(-not $cm.Success -or -not $nm.Success){exit 1}; $curCode=[int]$cm.Groups[1].Value; $maj=[int]$nm.Groups[1].Value; $min=[int]$nm.Groups[2].Value; $pat=[int]$nm.Groups[3].Value; $curVer=($maj,$min,$pat -join '.'); $pat++; if($pat -ge 10){$pat=0;$min++;if($min -ge 10){$min=0;$maj++}}; $nextVer=($maj,$min,$pat -join '.'); $nextCode=$curCode+1; [IO.File]::WriteAllText('%VER_TMP%', ($curVer,$curCode,$nextVer,$nextCode -join '|'))"
if errorlevel 1 (
    if exist "%VER_TMP%" del "%VER_TMP%" >nul 2>&1
    echo [ERROR] Could not parse versionCode / versionName from %GRADLE_FILE%.
    exit /b 1
)

for /f "usebackq tokens=1,2,3,4 delims=|" %%a in ("%VER_TMP%") do (
    set "CUR_VER=%%a"
    set "CUR_CODE=%%b"
    set "NEXT_VER=%%c"
    set "NEXT_CODE=%%d"
)
if exist "%VER_TMP%" del "%VER_TMP%" >nul 2>&1

if "%NEXT_VER%"=="" (
    echo [ERROR] Parsed version is empty.
    exit /b 1
)

set "APK_OUT=IntactFolio-v%NEXT_VER%.apk"
set "AAB_OUT=IntactFolio-v%NEXT_VER%.aab"

echo ==========================================
echo  IntactFolio Release Script
echo  Current : %CUR_VER% ^(versionCode %CUR_CODE%^)
echo  Next    : %NEXT_VER% ^(versionCode %NEXT_CODE%^)
echo  Build   : gradlew.bat testDebugUnitTest assembleRelease bundleRelease
echo  Outputs : %APK_OUT% , %AAB_OUT%
echo ==========================================

if "%DRY_RUN%"=="1" (
    echo [DRY-RUN] No files modified and no build executed.
    exit /b 0
)

powershell -NoProfile -Command "$q=[char]34; $p='%GRADLE_FILE%'; $text=[IO.File]::ReadAllText($p); $text=[regex]::Replace($text,'versionCode\s*=\s*\d+','versionCode = %NEXT_CODE%',1); $text=[regex]::Replace($text,'versionName\s*=\s*'+$q+'\d+\.\d+\.\d+'+$q,'versionName = '+$q+'%NEXT_VER%'+$q,1); [IO.File]::WriteAllText($p,$text,[System.Text.UTF8Encoding]::new($false))"
if errorlevel 1 (
    echo [ERROR] Failed to update version in %GRADLE_FILE%.
    exit /b 1
)

call gradlew.bat testDebugUnitTest assembleRelease bundleRelease
if errorlevel 1 (
    echo [ERROR] Gradle build or tests failed.
    exit /b 1
)

set "SRC_APK=app\build\outputs\apk\release\app-release.apk"
set "SRC_AAB=app\build\outputs\bundle\release\app-release.aab"

if not exist "%SRC_APK%" (
    echo [ERROR] Release APK not found at %SRC_APK%.
    exit /b 1
)
if not exist "%SRC_AAB%" (
    echo [ERROR] Release AAB not found at %SRC_AAB%.
    exit /b 1
)

copy /y "%SRC_APK%" "%APK_OUT%" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy %SRC_APK% to %APK_OUT%.
    exit /b 1
)

copy /y "%SRC_AAB%" "%AAB_OUT%" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy %SRC_AAB% to %AAB_OUT%.
    exit /b 1
)

echo [SUCCESS] Release v%NEXT_VER% ^(versionCode %NEXT_CODE%^) built successfully:
echo   - APK: %CD%\%APK_OUT%
echo   - AAB: %CD%\%AAB_OUT%
exit /b 0
