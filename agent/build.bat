@echo off
chcp 65001 >nul
REM ── Minecraft Client Update Java Agent Build Script (Windows) ────
REM Phase 1 (JavaFX helper JVM) on the latest-upstream baseline: compiles the
REM gui/javafx package against the bundled JavaFX 21 build jars and produces two
REM JARs:
REM   UpdateAgent.jar        launcher (javaagent bootstrap, never updated)
REM   UpdateAgent_core.jar   core + gui/javafx helper classes + embedded
REM                          /ui.css + /images + /javafx-runtime-spec.json
REM The helper JVM runs on the core JAR (its -cp). JavaFX 21 runtime jars
REM (javafx-base/graphics/controls/swing, win classifier) are auto-downloaded
REM into .\lib\javafx\ from Maven Central when missing. The Minecraft JVM never
REM loads javafx.*: only JavaFxEntryPoint / JavaFxUpdateView import JavaFX, and
REM those are only loaded inside the helper JVM.
REM Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
REM ──────────────────────────────────────────────────────────────────

setlocal EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
set "SRC_DIR=%SCRIPT_DIR%src"
set "JAVAFX_RES_DIR=%SCRIPT_DIR%javafx"
set "JAVAFX_LIB_DIR=%SCRIPT_DIR%lib\javafx"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "LAUNCHER_JAR=%SCRIPT_DIR%UpdateAgent.jar"
set "CORE_JAR=%SCRIPT_DIR%UpdateAgent_core.jar"
set "RUNTIME_SPEC=%JAVAFX_RES_DIR%\javafx-runtime-spec.json"

REM Auto-download the JavaFX 21 build jars from Maven Central if missing
REM (keeps lib/javafx out of the repo; fresh clones build without manual steps).
set "JAVAFX_VERSION=21.0.4"
set "JAVAFX_CLASSIFIER=win"
set "JAVAFX_MAVEN=https://repo1.maven.org/maven2/org/openjfx"

if not exist "%JAVAFX_LIB_DIR%" mkdir "%JAVAFX_LIB_DIR%"
for %%m in (javafx-base javafx-graphics javafx-controls javafx-swing) do (
    set "JAVAFX_FILE=%%m-%JAVAFX_VERSION%-%JAVAFX_CLASSIFIER%.jar"
    if not exist "%JAVAFX_LIB_DIR%\!JAVAFX_FILE!" (
        echo [build] Downloading !JAVAFX_FILE! from Maven Central...
        curl -fSL -o "%JAVAFX_LIB_DIR%\!JAVAFX_FILE!" "%JAVAFX_MAVEN%/%%m/%JAVAFX_VERSION%/!JAVAFX_FILE!"
        if errorlevel 1 (
            echo [build] ERROR: failed to download !JAVAFX_FILE!
            echo [build] Get it manually from: %JAVAFX_MAVEN%/%%m/%JAVAFX_VERSION%/
            exit /b 1
        )
    )
)
if not exist "%RUNTIME_SPEC%" (
    echo [build] ERROR: missing embedded runtime spec: %RUNTIME_SPEC%
    exit /b 1
)

echo [build] Compiling core + gui/javafx helper...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"
REM Response-file paths must be quoted (paths may contain spaces) AND use
REM forward slashes: javac treats `\` as an escape character in @argfiles,
REM so backslashes inside quoted paths get silently stripped. Write each
REM .java path quoted, with backslashes converted to forward slashes.
(
    for /r "%SRC_DIR%" %%f in (*.java) do (
        set "_src=%%f"
        echo "!_src:\=/!"
    )
) > "%BUILD_DIR%\sources.txt"
javac --release 17 -encoding UTF-8 -cp "lib\javafx\*" -d "%BUILD_DIR%" @"%BUILD_DIR%\sources.txt"
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)
REM Bundle the stylesheet + illustrations + runtime spec so the helper can load them.
copy /y "%JAVAFX_RES_DIR%\ui.css" "%BUILD_DIR%\ui.css" >nul
xcopy /e /i /y "%SCRIPT_DIR%images" "%BUILD_DIR%\images" >nul
copy /y "%RUNTIME_SPEC%" "%BUILD_DIR%\javafx-runtime-spec.json" >nul

echo [build] Packaging launcher JAR...
cd /d "%BUILD_DIR%"
del /q "sources.txt" 2>nul
jar cfm "%LAUNCHER_JAR%" "%SCRIPT_DIR%META-INF\MANIFEST.MF" Launcher.class
if %ERRORLEVEL% neq 0 (
    echo [build] Launcher JAR packaging failed!
    exit /b 1
)

echo [build] Packaging core JAR ^(everything except the launcher^) + css/images/runtime spec...
REM Keep the launcher out of the self-updatable core, but include the default
REM package UpdateAgent compatibility facade and all named-package classes.
jar cf "%CORE_JAR%" UpdateAgent.class com ui.css images javafx-runtime-spec.json
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
