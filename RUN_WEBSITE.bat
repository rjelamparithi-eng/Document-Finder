@echo off
cd /d "%~dp0"
echo =============================================
echo      ARAM OFFICE - LOCALHOST WEBSITE
 echo =============================================
if not exist out mkdir out
echo Compiling Java source files...
javac --add-modules jdk.httpserver -cp "lib\*" -d out src\*.java
if errorlevel 1 (
  echo.
  echo Compilation failed. Make sure JDK 21 is installed.
  pause
  exit /b 1
)
echo.
set PORT=%~1
if "%PORT%"=="" set PORT=8080
echo Open your browser at http://127.0.0.1:%PORT%
echo Keep this terminal open while using the website.
echo Press Ctrl+C to stop.
echo.
java --add-modules jdk.httpserver -cp "out;lib\*" WebServer %PORT%
pause
