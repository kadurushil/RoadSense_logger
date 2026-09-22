@echo off
REM =========================================================================
REM RoadSense Presentation Generator Runner
REM Regenerates RoadSense_Executive_Progress.pptx inside decks/
REM =========================================================================

echo [RoadSense] Generating Executive Progress Presentation Deck...
cd /d "%~dp0"

node generate_roadsense_deck.js

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [RoadSense] SUCCESS: Presentation regenerated at:
    echo            %~dp0decks\RoadSense_Executive_Progress.pptx
    echo.
) else (
    echo.
    echo [RoadSense] ERROR: Failed to generate presentation. Error code: %ERRORLEVEL%
    echo.
)

pause
