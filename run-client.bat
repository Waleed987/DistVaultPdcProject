@echo off
title DIST-VAULT Client
echo ==========================================
echo   DIST-VAULT  —  Client Application
echo ==========================================
cd /d "%~dp0"
java -cp bin Client
pause
