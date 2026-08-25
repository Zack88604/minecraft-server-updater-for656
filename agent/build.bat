@echo off
chcp 65001 >nul
REM ── Minecraft Client Update Java Agent Build Script (Windows) ────
REM Usage: build.bat
REM Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
REM ──────────────────────────────────────────────────────────────────

setlocal
set "SCRIPT_DIR=%~dp0"
set "SRC_DIR=%SCRIPT_DIR%src"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "LAUNCHER_JAR=%SCRIPT_DIR%UpdateAgent.jar"
set "CORE_JAR=%SCRIPT_DIR%UpdateAgent_core.jar"

echo [build] Compiling...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"
dir /s /b "%SRC_DIR%\*.java" > "%BUILD_DIR%\sources.txt"
javac -d "%BUILD_DIR%" @"%BUILD_DIR%\sources.txt"
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)

echo [build] Packaging launcher JAR...
cd /d "%BUILD_DIR%"
del /q "sources.txt" 2>nul
jar cfm "%LAUNCHER_JAR%" "%SCRIPT_DIR%META-INF\MANIFEST.MF" Launcher.class
if %ERRORLEVEL% neq 0 (
    echo [build] Launcher JAR packaging failed!
    exit /b 1
)

echo [build] Packaging core JAR...
REM Keep the launcher out of the self-updatable core, but include the default
REM package UpdateAgent compatibility facade and all named-package classes.
jar cf "%CORE_JAR%" UpdateAgent.class com
if %ERRORLEVEL% neq 0 (
    echo [build] Core JAR packaging failed!
    exit /b 1
)

echo [build] Done!
echo [build] Launcher: %LAUNCHER_JAR%
echo [build] Core:     %CORE_JAR%

REM Clean up temp files
cd /d "%SCRIPT_DIR%"
rmdir /s /q "%BUILD_DIR%" 2>nul
endlocal
