@echo off
cd /d "%~dp0"
if not exist out mkdir out
echo Compiling Java files...
javac -cp "lib\*" -d out src\*.java
if errorlevel 1 (
    echo Java compilation failed. Check JDK 21 installation.
    pause
    exit /b 1
)
echo Starting Aram Office application...
java -cp "out;lib\*" Main
pause
