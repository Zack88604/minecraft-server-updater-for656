@echo off
chcp 65001 >nul
REM ── Minecraft Client Update Java Agent Build Script (Windows) ────
REM Usage: build.bat
REM Output: UpdateAgent.jar
REM ──────────────────────────────────────────────────────────────────

setlocal
set "SCRIPT_DIR=%~dp0"
set "SRC_DIR=%SCRIPT_DIR%src"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "OUTPUT_JAR=%SCRIPT_DIR%UpdateAgent.jar"

echo [build] Compiling UpdateAgent.java...
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
javac -d "%BUILD_DIR%" "%SRC_DIR%\UpdateAgent.java" "%SRC_DIR%\ReplaceHelper.java"
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)

echo [build] Packaging into JAR...
cd /d "%BUILD_DIR%"
jar cfm "%OUTPUT_JAR%" "%SCRIPT_DIR%META-INF\MANIFEST.MF" *.class
if %ERRORLEVEL% neq 0 (
    echo [build] JAR packaging failed!
    exit /b 1
)

echo [build] Done! Created: %OUTPUT_JAR%

REM Clean up temp files
rmdir /s /q "%BUILD_DIR%" 2>nul
endlocal
