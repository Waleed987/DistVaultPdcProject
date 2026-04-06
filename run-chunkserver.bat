@echo off
title DIST-VAULT Chunk Server %1
echo ==========================================
echo   DIST-VAULT  —  Chunk Server %1
echo ==========================================
cd /d "%~dp0"
if "%1"=="" (
    echo Usage: run-chunkserver.bat ^<serverId^>
    echo Example: run-chunkserver.bat 1
    pause
    exit /b 1
)
java -cp bin ChunkServer %1
pause
