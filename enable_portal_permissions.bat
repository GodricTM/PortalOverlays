@echo off
powershell.exe -ExecutionPolicy Bypass -File "%~dp0enable_portal_permissions.ps1" %*
echo.
pause
