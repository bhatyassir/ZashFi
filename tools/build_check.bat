@echo off
rem Builds the app, runs every unit test, and installs the debug app on a USB phone if one is attached.
rem Output goes to build_check.log (git-ignored) so it can be read without copy and paste.
cd /d "%~dp0.."
echo Building and testing. This takes a few minutes...
call "%~dp0..\gradlew.bat" test assembleDebug --console=plain > build_check.log 2>&1
set RC=%ERRORLEVEL%
>>build_check.log echo GRADLE_EXIT=%RC%
python tools\test_m7_scrubber.py >> build_check.log 2>&1
>>build_check.log echo SCRUBBER_EXIT=%ERRORLEVEL%
if not "%RC%"=="0" goto done
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk >> build_check.log 2>&1
>>build_check.log echo INSTALL_EXIT=%ERRORLEVEL%
:done
echo Finished. Build exit code: %RC%
