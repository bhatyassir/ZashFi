@echo off
setlocal EnableExtensions
rem Builds the app, runs every unit test, and installs the debug app on a USB phone if one is attached.
rem Output goes to build_check.log (git-ignored) so it can be read without copy and paste.
cd /d "%~dp0.."
echo Building and testing. This takes a few minutes...

call "%~dp0..\gradlew.bat" test assembleDebug --console=plain > build_check.log 2>&1
set "RC=%ERRORLEVEL%"
>>build_check.log echo GRADLE_EXIT=%RC%
if not "%RC%"=="0" goto fail

set "ADB_EXE="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_EXE=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_EXE if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_EXE if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB_EXE=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB_EXE (
    for /f "delims=" %%A in ('where adb 2^>nul') do if not defined ADB_EXE set "ADB_EXE=%%~fA"
)

if not defined ADB_EXE (
    >>build_check.log echo ADB_NOT_FOUND: checked LOCALAPPDATA, ANDROID_HOME, ANDROID_SDK_ROOT, and PATH
    set "RC=1"
    goto fail
)

"%ADB_EXE%" install -r app\build\outputs\apk\debug\app-debug.apk >> build_check.log 2>&1
set "RC=%ERRORLEVEL%"
>>build_check.log echo INSTALL_EXIT=%RC%
if not "%RC%"=="0" goto fail

echo Finished. Build and install succeeded.
exit /b 0

:fail
echo Failed with exit code: %RC%. See build_check.log for details.
exit /b %RC%
