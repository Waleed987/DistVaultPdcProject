@echo off
title DIST-VAULT Build
echo ==========================================
echo   DIST-VAULT  —  Compiling Sources
echo ==========================================

if not exist bin mkdir bin

echo Compiling all .java files...
javac -d bin -sourcepath src src\Protocol.java src\AuditLogger.java src\UserManager.java src\EncryptionManager.java src\MasterNode.java src\ChunkServer.java src\DashPanel.java src\UploadPanel.java src\SearchPanel.java src\AdminPanel.java src\Client.java

if %ERRORLEVEL% == 0 (
    echo.
    echo [OK] Compilation successful! Class files are in the bin\ folder.
    echo.
    echo How to run:
    echo   1. run-master.bat          (start Master Node)
    echo   2. run-chunkserver.bat 1   (start Chunk Server 1)
    echo   3. run-chunkserver.bat 2   (start Chunk Server 2)
    echo   4. run-chunkserver.bat 3   (start Chunk Server 3)
    echo   5. run-client.bat          (start GUI Client)
) else (
    echo.
    echo [FAIL] Compilation errors. Fix the errors above and try again.
)
pause
