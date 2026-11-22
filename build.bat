@echo off
setlocal
cd /d "%~dp0"

rem 需要 JDK 21+（Gradle 用它编译/运行）；单文件 exe 由 .NET Framework 的 csc 生成。
where java >nul 2>nul
if errorlevel 1 (
    echo [SweetPotatoClient-Updater] java not found. Please install JDK 21+ and add it to PATH.
    pause
    exit /b 1
)

call gradlew.bat --no-daemon --console=plain clean build
if errorlevel 1 (
    echo.
    echo [SweetPotatoClient-Updater] Build failed.
    pause
    exit /b 1
)

echo.
echo [SweetPotatoClient-Updater] Build done.
echo   update.exe  -^> %~dp0..\update.exe
echo   update.jar  -^> %~dp0build\libs\
echo.
echo 单文件 update.exe 已放到 SweetPotatoClient 根目录，运行时使用客户端自带的 JDK。
pause
endlocal
