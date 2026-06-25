@echo off
echo Stopping Smart Factory collector...
taskkill /f /im java.exe >nul 2>&1
if %errorlevel% == 0 (
    echo Done.
) else (
    echo No java process found.
)
pause
