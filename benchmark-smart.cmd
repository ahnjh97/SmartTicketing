@echo off
setlocal
if not "%~1"=="" goto runWithArgs
echo.
echo 1. Before improvement
echo 2. After improvement
echo.
choice /c 12 /n /m "Select [1/2]: "
if errorlevel 255 exit /b 1
if errorlevel 2 goto runAfter
if errorlevel 1 goto runBefore
exit /b 1
:runBefore
node "%~dp0scripts\smart-booking-benchmark\quick.mjs" before
goto done
:runAfter
node "%~dp0scripts\smart-booking-benchmark\quick.mjs" after
goto done
:runWithArgs
node "%~dp0scripts\smart-booking-benchmark\quick.mjs" %*
:done
set "benchmarkExit=%ERRORLEVEL%"
if not "%benchmarkExit%"=="0" echo Measurement failed. See the message above.
pause
exit /b %benchmarkExit%
