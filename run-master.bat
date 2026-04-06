@echo off
title DIST-VAULT Master Node
echo ==========================================
echo   DIST-VAULT  —  Master Node
echo ==========================================
cd /d "%~dp0"
java -cp bin MasterNode
pause
