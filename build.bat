@echo off
setlocal
cd /d "%~dp0"

rem 需要一个带 jpackage 的 JDK 21+
set "HAS_JPACKAGE="
where jpackage >nul 2>nul && set "HAS_JPACKAGE=1"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "HAS_JPACKAGE=1"
if not defined HAS_JPACKAGE (
    echo [SweetPotatoClient-Updater] jpackage not found.
    echo Please install JDK 21+ ^(with jpackage^) and set JAVA_HOME, or add its bin to PATH.
    pause
    exit /b 1
)

call gradlew.bat --no-daemon --console=plain build packageExe
if errorlevel 1 (
    echo.
    echo [SweetPotatoClient-Updater] Build failed.
    pause
    exit /b 1
)

echo.
echo [SweetPotatoClient-Updater] Build done: dist\update\update.exe
pause
endlocal
