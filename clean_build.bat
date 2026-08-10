@echo off
echo Stopping Gradle daemon...
call gradlew --stop 2>nul

echo Killing Java processes...
taskkill /f /im java.exe 2>nul
taskkill /f /im javaw.exe 2>nul

echo Waiting for processes to stop...
timeout /t 5 /nobreak >nul

echo Cleaning build directories...
if exist app\build (
    echo Removing app\build...
    rmdir /s /q app\build
)
if exist data-importers\build (
    echo Removing data-importers\build...
    rmdir /s /q data-importers\build
)
if exist rsvp-engine\build (
    echo Removing rsvp-engine\build...
    rmdir /s /q rsvp-engine\build
)
if exist core-ui\build (
    echo Removing core-ui\build...
    rmdir /s /q core-ui\build
)
if exist .gradle (
    echo Removing .gradle cache...
    rmdir /s /q .gradle
)
if exist build (
    echo Removing root build...
    rmdir /s /q build
)

echo.
echo Build directories cleaned successfully!
echo.
echo You can now try building with:
echo   gradlew assembleDebug
echo.
pause