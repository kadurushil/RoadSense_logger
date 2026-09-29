@echo off
REM =========================================================================
REM RoadSense Presentation Generator Runner - Week 2
REM Regenerates RoadSense_Week2_Executive_Progress.pptx inside decks/
REM =========================================================================

echo [RoadSense] Generating Week 2 Executive & Technical Progress Presentation Deck...
cd /d "%~dp0"

node generate_roadsense_week2_deck.js

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [RoadSense] SUCCESS: Week 2 presentation regenerated at:
    echo            %~dp0decks\RoadSense_Week2_Executive_Progress.pptx
    echo.
) else (
    echo.
    echo [RoadSense] ERROR: Failed to generate Week 2 presentation. Error code: %ERRORLEVEL%
    echo.
)

pause
