@echo off
rem Self-signed HTTPS certificate for the KubGAU journal server (valid 10 years).
rem Usage: scripts\generate-cert.bat [extra IP or host name ...]
rem All the work is done by generate-cert.ps1 (PowerShell ships with Windows 10/11).
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0generate-cert.ps1" %*
set RC=%ERRORLEVEL%
echo.
pause
exit /b %RC%
