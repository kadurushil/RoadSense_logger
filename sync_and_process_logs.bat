@echo off
setlocal
title RoadSense - Log Sync, Visualizer & Foxglove MCAP Pipeline

cd /d "%~dp0"

:: Auto-detect dedicated roadsense-mcap Python 3.12 virtual environment
set "PYTHON_CMD=python"
if exist "%USERPROFILE%\.conda\envs\roadsense-mcap\python.exe" (
    set "PYTHON_CMD=%USERPROFILE%\.conda\envs\roadsense-mcap\python.exe"
)

echo ===============================================================================
echo          RoadSense - Log Sync, Visualizer & Foxglove MCAP Pipeline
echo ===============================================================================
echo Python Interpreter: %PYTHON_CMD%
echo.
echo Select an operation:
echo.
echo   [1] Full Pipeline: Sync from Device (ADB) and Process New Sessions
echo   [2] Sync Only: Pull sessions from Device without processing
echo   [3] Process Only: Process un-processed local sessions (skips up-to-date)
echo   [4] Force Re-process All: Re-generate all local session visualizer files
echo   [5] Process Specific Session: Choose a specific session to process
echo   [6] Export to Foxglove MCAP: Convert sessions into .mcap container
echo   [7] Exit
echo.

choice /c 1234567 /n /m "Enter your choice [1-7]: "

if errorlevel 7 goto run_exit
if errorlevel 6 goto run_mcap
if errorlevel 5 goto run_specific
if errorlevel 4 goto run_force_all
if errorlevel 3 goto run_process_only
if errorlevel 2 goto run_sync_only
if errorlevel 1 goto run_full

:run_full
echo.
echo [*] Running Full Pipeline (Sync + Process New)...
"%PYTHON_CMD%" tools\sync_and_process_sessions.py --mcap
goto done

:run_sync_only
echo.
echo [*] Running Sync Only...
"%PYTHON_CMD%" tools\sync_and_process_sessions.py --sync-only
goto done

:run_process_only
echo.
echo [*] Running Process Only (Skipping up-to-date sessions)...
"%PYTHON_CMD%" tools\sync_and_process_sessions.py --process-only --mcap
goto done

:run_force_all
echo.
echo [*] Force Re-processing all local sessions...
"%PYTHON_CMD%" tools\sync_and_process_sessions.py --process-only --force-all --mcap
goto done

:run_specific
echo.
echo Available Local Sessions:
echo -------------------------------------------------------------------------------
dir /b /ad logs\session_* 2>nul
echo -------------------------------------------------------------------------------
echo.
set /p SESS="Enter Session Folder Name (e.g. session_20260922_120145): "
if "%SESS%"=="" (
    echo [-] No session entered. Exiting.
    goto done
)
echo.
echo [*] Processing session: %SESS%...
"%PYTHON_CMD%" tools\sync_and_process_sessions.py --process-only --force --session "%SESS%" --mcap
goto done

:run_mcap
echo.
echo Available Local Sessions for MCAP Export:
echo -------------------------------------------------------------------------------
dir /b /ad logs\session_* 2>nul
echo -------------------------------------------------------------------------------
echo.
set /p MCAP_SESS="Enter Session Folder Name (or press ENTER for ALL sessions): "
if "%MCAP_SESS%"=="" (
    echo [*] Converting ALL sessions to Foxglove MCAP...
    "%PYTHON_CMD%" tools\sync_and_process_sessions.py --process-only --force-all --mcap
) else (
    echo [*] Converting %MCAP_SESS% to Foxglove MCAP...
    "%PYTHON_CMD%" tools\convert_session_to_mcap.py "logs\%MCAP_SESS%"
)
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
