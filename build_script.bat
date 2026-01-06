Rem For Windows (save as build.bat)
@echo off
echo Building IP Whitelist Mod...
echo ================================
gradlew.bat build
echo ================================
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✓ Build successful!
    echo Your mod is ready at: build\libs\
    echo Copy the .jar file to your server's mods folder
) else (
    echo.
    echo ✗ Build failed! Check the error messages above.
    echo Press any key to see more details or close...
)
echo.
echo Press any key to close this window...
pause >nul