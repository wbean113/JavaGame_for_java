@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set "GAME_EXIT=%ERRORLEVEL%"
if not "%GAME_EXIT%"=="0" echo Build failed. Read the error above and README.md.
exit /b %GAME_EXIT%
