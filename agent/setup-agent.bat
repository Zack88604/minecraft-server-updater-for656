@echo off
chcp 65001 >nul
REM ── Minecraft Client Auto-Update Agent Setup Script (Windows) ────
REM Appends -javaagent configuration to Minecraft client JVM arguments.
REM
REM Usage:
REM   setup-agent.bat <minecraft-instance-dir> [update-server-url]
REM
REM Example:
REM   setup-agent.bat C:\Users\You\AppData\Roaming\.minecraft http://192.168.1.100:25565
REM
REM This script appends the -javaagent argument to user_jvm_args.txt
REM (or vmoptions file) inside the instance directory.
REM If the file does not exist, it will be created.
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

REM Find JVM arguments file
set JVM_ARGS_FILE=
if exist "%INSTANCE_DIR%\user_jvm_args.txt" (
    set JVM_ARGS_FILE=%INSTANCE_DIR%\user_jvm_args.txt
) else if exist "%INSTANCE_DIR%\options.txt" (
    set JVM_ARGS_FILE=%INSTANCE_DIR%\options.txt
) else (
    set JVM_ARGS_FILE=%INSTANCE_DIR%\user_jvm_args.txt
    type nul > "%JVM_ARGS_FILE%" 2>nul
)

set AGENT_ARG=-javaagent:%AGENT_JAR%=server=%SERVER_URL%

REM Check if already configured
findstr /C:"UpdateAgent" "%JVM_ARGS_FILE%" >nul 2>&1
if !ERRORLEVEL! equ 0 (
    echo [setup] Agent already configured in %JVM_ARGS_FILE%
    echo [setup] To update, edit the line manually:
    echo        %AGENT_ARG%
) else (
    echo %AGENT_ARG%>> "%JVM_ARGS_FILE%"
    echo [setup] Added agent to %JVM_ARGS_FILE%
)

echo [setup] Done!
echo [setup] Agent JAR: %AGENT_JAR%
echo [setup] Server:    %SERVER_URL%
echo.
echo Next time you launch Minecraft, updates will be checked automatically.
endlocal
