@echo off
title DIST-VAULT Launcher
cd /d "%~dp0"

echo ==========================================
echo   DIST-VAULT  —  Starting All Components
echo ==========================================

echo [1/5] Compiling sources...
javac -d bin -sourcepath src src\Protocol.java src\AuditLogger.java src\UserManager.java src\EncryptionManager.java src\MasterNode.java src\ChunkServer.java src\DashPanel.java src\UploadPanel.java src\SearchPanel.java src\AdminPanel.java src\Client.java
if %ERRORLEVEL% NEQ 0 (
    echo [FAIL] Compile errors. Fix them and run again.
    pause
    exit /b 1
)
echo [OK] Compiled successfully.

echo [2/5] Starting Master Node...
start "DIST-VAULT Master" cmd /k "cd /d %~dp0 && java -cp bin MasterNode"
timeout /t 3 /nobreak > nul

echo [3/5] Starting Chunk Servers...
start "ChunkServer-1" cmd /k "cd /d %~dp0 && java -cp bin ChunkServer 1"
start "ChunkServer-2" cmd /k "cd /d %~dp0 && java -cp bin ChunkServer 2"
start "ChunkServer-3" cmd /k "cd /d %~dp0 && java -cp bin ChunkServer 3"
timeout /t 3 /nobreak > nul

echo [4/5] Starting Client GUI...
start "DIST-VAULT Client" cmd /k "cd /d %~dp0 && java -cp bin Client"

echo.
echo [5/5] All components started!
echo.
echo  Default Logins:
echo   admin   / admin123   (Admin)
echo   doctor1 / doctor123  (Doctor)
echo   nurse1  / nurse123   (Nurse)
echo   patient1/ patient123 (Patient)
echo.
echo  Close this window anytime — the other windows keep running.
