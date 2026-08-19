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
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
javac -d "%BUILD_DIR%" "%SRC_DIR%\Launcher.java" "%SRC_DIR%\UpdateAgent.java"
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)

echo [build] Packaging launcher JAR...
cd /d "%BUILD_DIR%"
jar cfm "%LAUNCHER_JAR%" "%SCRIPT_DIR%META-INF\MANIFEST.MF" Launcher.class Launcher$*.class 2>nul
if %ERRORLEVEL% neq 0 (
    echo [build] Launcher JAR packaging failed!
    exit /b 1
)

echo [build] Packaging core JAR...
REM Temporarily exclude Launcher classes from core JAR
if exist Launcher.class ren Launcher.class Launcher.class.exclude
if exist Launcher$1.class ren Launcher$1.class Launcher$1.class.exclude 2>nul
jar cf "%CORE_JAR%" *.class
if %ERRORLEVEL% neq 0 (
    echo [build] Core JAR packaging failed!
    exit /b 1
)
REM Restore Launcher classes
if exist Launcher.class.exclude ren Launcher.class.exclude Launcher.class
if exist Launcher$1.class.exclude ren Launcher$1.class.exclude Launcher$1.class 2>nul

echo [build] Done!
echo [build] Launcher: %LAUNCHER_JAR%
echo [build] Core:     %CORE_JAR%

REM Clean up temp files
cd /d "%SCRIPT_DIR%"
rmdir /s /q "%BUILD_DIR%" 2>nul
endlocal
