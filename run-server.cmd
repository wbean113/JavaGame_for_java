@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-server.ps1" %*
set "GAME_EXIT=%ERRORLEVEL%"
if not "%GAME_EXIT%"=="0" pause
exit /b %GAME_EXIT%
