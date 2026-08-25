@echo off
chcp 65001 >nul
REM ── Minecraft Client Update Java Agent Build Script (Windows) ────
REM v3 (pure client bootstrap): always compiles the JavaFX helper view and
REM produces two JARs:
REM   UpdateAgent.jar        launcher (javaagent bootstrap, never updated)
REM   UpdateAgent_core.jar   core + JavaFX helper view + embedded
REM                          /javafx-runtime-spec.json (the helper's -cp)
REM The helper JVM runs on the core JAR. JavaFX 21 runtime jars
REM (javafx-base/graphics/controls/swing, win classifier) are auto-downloaded
REM into .\lib\javafx\ from Maven Central when missing; the release's
REM javafx-runtime-spec.json is embedded verbatim into the core JAR as the
REM pure-client bootstrap anchor (the server manifest is never involved in
REM the JavaFX runtime).
REM Output: UpdateAgent.jar (launcher) + UpdateAgent_core.jar (core)
REM ──────────────────────────────────────────────────────────────────

setlocal EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
set "SRC_DIR=%SCRIPT_DIR%src"
set "JAVAFX_SRC_DIR=%SCRIPT_DIR%javafx"
set "JAVAFX_LIB_DIR=%SCRIPT_DIR%lib\javafx"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "LAUNCHER_JAR=%SCRIPT_DIR%UpdateAgent.jar"
set "CORE_JAR=%SCRIPT_DIR%UpdateAgent_core.jar"
set "RUNTIME_SPEC=%JAVAFX_SRC_DIR%\javafx-runtime-spec.json"

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

echo [build] Compiling core + JavaFX helper view...
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
mkdir "%BUILD_DIR%"
javac -encoding UTF-8 -cp "lib\javafx\*" -d "%BUILD_DIR%" "%SRC_DIR%\*.java" "%JAVAFX_SRC_DIR%\*.java"
if %ERRORLEVEL% neq 0 (
    echo [build] Compilation failed!
    exit /b 1
)
REM Bundle the stylesheet + illustrations so the helper view can load them.
copy /y "%JAVAFX_SRC_DIR%\ui.css" "%BUILD_DIR%\ui.css" >nul
xcopy /e /i /y "%SCRIPT_DIR%images" "%BUILD_DIR%\images" >nul
REM Embed the release's JavaFX runtime spec (pure client bootstrap anchor).
copy /y "%RUNTIME_SPEC%" "%BUILD_DIR%\javafx-runtime-spec.json" >nul

echo [build] Packaging launcher JAR...
cd /d "%BUILD_DIR%"
jar cfm "%LAUNCHER_JAR%" "%SCRIPT_DIR%META-INF\MANIFEST.MF" Launcher.class
if %ERRORLEVEL% neq 0 (
    echo [build] Launcher JAR packaging failed!
    exit /b 1
)

echo [build] Packaging core JAR ^(everything except the launcher^) + css/images/runtime spec...
REM Exclude Launcher.class from the core JAR
if exist Launcher.class ren Launcher.class Launcher.class.exclude
jar cf "%CORE_JAR%" *.class ui.css images javafx-runtime-spec.json
if %ERRORLEVEL% neq 0 (
    echo [build] Core JAR packaging failed!
    exit /b 1
)
REM Restore Launcher class
if exist Launcher.class.exclude ren Launcher.class.exclude Launcher.class

cd /d "%SCRIPT_DIR%"
rmdir /s /q "%BUILD_DIR%" 2>nul

echo [build] Done!
echo [build] Launcher: %LAUNCHER_JAR%
echo [build] Core:     %CORE_JAR%
endlocal
