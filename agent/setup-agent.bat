@echo off
chcp 65001 >nul
REM ── Minecraft Client Auto-Update Agent Setup Script (Windows) ────
REM Creates mc-update.properties in the instance directory and appends
REM a minimal -javaagent JVM argument (no inline parameters).
REM
REM Usage:
REM   setup-agent.bat <minecraft-instance-dir> [update-server-url]
REM
REM Example:
REM   setup-agent.bat C:\Users\You\AppData\Roaming\.minecraft http://192.168.1.100:25565
REM ──────────────────────────────────────────────────────────────────

setlocal enabledelayedexpansion

if "%~1"=="" (
    echo Usage: %~nx0 ^<minecraft-instance-dir^> [update-server-url]
    echo Example: %~nx0 C:\Users\You\AppData\Roaming\.minecraft http://192.168.1.100:25565
    exit /b 1
)

set INSTANCE_DIR=%~1
set SERVER_URL=%~2
if "%SERVER_URL%"=="" set SERVER_URL=http://localhost:25565

REM Determine Agent JAR path (same directory as this script)
set AGENT_JAR=%~dp0UpdateAgent.jar

if not exist "%AGENT_JAR%" (
    echo [setup] ERROR: UpdateAgent.jar not found at %AGENT_JAR%
    echo [setup] Run build.bat first to generate the JAR.
    exit /b 1
)

REM ---- Write persistent config file ----
set CONFIG_FILE=%INSTANCE_DIR%\mc-update.properties
echo # Minecraft Update Agent Configuration> "%CONFIG_FILE%"
echo server=%SERVER_URL%>> "%CONFIG_FILE%"
echo [setup] Config written: %CONFIG_FILE%

REM ---- Add -javaagent JVM argument (JAR path only, no inline params) ----
set JVM_ARGS_FILE=
if exist "%INSTANCE_DIR%\user_jvm_args.txt" (
    set JVM_ARGS_FILE=%INSTANCE_DIR%\user_jvm_args.txt
) else if exist "%INSTANCE_DIR%\options.txt" (
    set JVM_ARGS_FILE=%INSTANCE_DIR%\options.txt
) else (
    set JVM_ARGS_FILE=%INSTANCE_DIR%\user_jvm_args.txt
    type nul > "%JVM_ARGS_FILE%" 2>nul
)

set AGENT_ARG=-javaagent:%AGENT_JAR%

REM Check if already configured
findstr /C:"UpdateAgent" "%JVM_ARGS_FILE%" >nul 2>&1
if !ERRORLEVEL! equ 0 (
    echo [setup] Agent already configured in %JVM_ARGS_FILE%
    echo [setup] To update config, edit: %CONFIG_FILE%
) else (
    echo %AGENT_ARG%>> "%JVM_ARGS_FILE%"
    echo [setup] Added agent to %JVM_ARGS_FILE%
)

echo [setup] Done!
echo [setup] Agent JAR: %AGENT_JAR%
echo [setup] Config:    %CONFIG_FILE%
echo [setup] Server:    %SERVER_URL%
echo.
echo Next time you launch Minecraft, updates will be checked automatically.
endlocal
