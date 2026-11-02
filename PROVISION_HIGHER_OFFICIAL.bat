@echo off
cd /d "%~dp0"
if not exist out mkdir out
echo Compiling source files...
javac -cp "lib\*" -d out src\*.java
if errorlevel 1 (
    echo Compilation failed.
    pause
    exit /b 1
)
echo.
java -cp "out;lib\*" AdminProvisioner %*
echo.
pause
