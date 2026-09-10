@echo off
setlocal
title RoadSense - Log Sync and Visualizer Pipeline

cd /d "%~dp0"

echo ===============================================================================
echo                RoadSense - Log Sync and Visualizer Pipeline
echo ===============================================================================
echo.
echo Select an operation:
echo.
echo   [1] Full Pipeline: Sync from Device (ADB) and Process New Sessions
echo   [2] Sync Only: Pull sessions from Device without processing
echo   [3] Process Only: Process un-processed local sessions (skips up-to-date)
echo   [4] Force Re-process All: Re-generate all local session visualizer files
echo   [5] Process Specific Session: Choose a specific session to process
echo   [6] Exit
echo.

choice /c 123456 /n /m "Enter your choice [1-6]: "

if errorlevel 6 goto run_exit
if errorlevel 5 goto run_specific
if errorlevel 4 goto run_force_all
if errorlevel 3 goto run_process_only
if errorlevel 2 goto run_sync_only
if errorlevel 1 goto run_full

:run_full
echo.
echo [*] Running Full Pipeline (Sync + Process New)...
python tools\sync_and_process_sessions.py
goto done

:run_sync_only
echo.
echo [*] Running Sync Only...
python tools\sync_and_process_sessions.py --sync-only
goto done

:run_process_only
echo.
echo [*] Running Process Only (Skipping up-to-date sessions)...
python tools\sync_and_process_sessions.py --process-only
goto done

:run_force_all
echo.
echo [*] Force Re-processing all local sessions...
python tools\sync_and_process_sessions.py --process-only --force-all
goto done

:run_specific
echo.
echo Available Local Sessions:
echo -------------------------------------------------------------------------------
dir /b /ad logs\session_* 2>nul
echo -------------------------------------------------------------------------------
echo.
set /p SESS="Enter Session Folder Name (e.g. session_20260910_093816): "
if "%SESS%"=="" (
    echo [-] No session entered. Exiting.
    goto done
)
echo.
echo [*] Processing session: %SESS%...
python tools\sync_and_process_sessions.py --process-only --force --session "%SESS%"
goto done

:run_exit
echo.
echo Exiting.
goto end_script

:done
echo.
echo ===============================================================================
echo Execution finished.
echo ===============================================================================
pause

:end_script
