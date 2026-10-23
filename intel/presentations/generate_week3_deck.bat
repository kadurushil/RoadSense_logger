@echo off
REM =========================================================================
REM RoadSense Presentation Generator Runner - Week 3
REM Regenerates RoadSense_Week3_Executive_Progress.pptx inside decks/
REM =========================================================================

echo [RoadSense] Generating Week 3 Executive and Technical Progress Presentation Deck...
cd /d "%~dp0"

node generate_roadsense_week3_deck.js

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [RoadSense] SUCCESS: Week 3 presentation regenerated at:
    echo            %~dp0decks\RoadSense_Week3_Executive_Progress.pptx
    echo.
) else (
    echo.
    echo [RoadSense] ERROR: Failed to generate Week 3 presentation. Error code: %ERRORLEVEL%
    echo.
)
