@echo off
chcp 65001 >nul
REM ── Minecraft 客户端更新 Java Agent 编译脚本 (Windows) ──────────
REM 用法: build.bat
REM 输出: UpdateAgent.jar
REM ──────────────────────────────────────────────────────────────────

setlocal
set "SCRIPT_DIR=%~dp0"
set "SRC_DIR=%SCRIPT_DIR%src"
set "BUILD_DIR=%SCRIPT_DIR%build"
set "OUTPUT_JAR=%SCRIPT_DIR%UpdateAgent.jar"

echo [build] Compiling UpdateAgent.java...
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
javac -d "%BUILD_DIR%" "%SRC_DIR%\UpdateAgent.java"
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

REM 清理临时文件
rmdir /s /q "%BUILD_DIR%" 2>nul
endlocal
