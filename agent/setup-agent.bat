@echo off
chcp 65001 >nul
REM ── Minecraft 客户端自动更新 Agent 安装脚本 (Windows) ───────────
REM 为 Minecraft 客户端的启动参数添加 -javaagent 配置
REM
REM 用法:
REM   setup-agent.bat <minecraft-instance-dir> [update-server-url]
REM
REM 示例:
REM   setup-agent.bat C:\Users\You\AppData\Roaming\.minecraft http://192.168.1.100:25565
REM
REM 该脚本会在实例目录的 user_jvm_args.txt 或 vmoptions 文件中追加
REM -javaagent 参数。如果文件不存在，会创建一个新的。
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

REM 确定 Agent JAR 路径（与脚本同目录）
set AGENT_JAR=%~dp0UpdateAgent.jar

if not exist "%AGENT_JAR%" (
    echo [setup] ERROR: UpdateAgent.jar not found at %AGENT_JAR%
    echo [setup] Run build.bat first to generate the JAR.
    exit /b 1
)

REM 寻找 JVM 参数文件
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

REM 检查是否已经添加过
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
echo 下次启动 Minecraft 客户端时，将自动检查更新。
endlocal
