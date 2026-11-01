@echo off
cd /d "%~dp0"
if not exist out mkdir out
echo Compiling source files and tests...
javac -cp "lib\*" -d out src\*.java tests\*.java
if errorlevel 1 (
    echo Compilation failed.
    pause
    exit /b 1
)
echo.
echo =========================================
echo [1/2] RUNNING DATA STRUCTURE TESTS...
echo =========================================
java -cp "out;lib\*" TestProject
if errorlevel 1 (
    echo Data structure tests failed.
    pause
    exit /b 1
)
echo.
echo =========================================
echo [2/2] RUNNING MYSQL & PDF UPGRADE TESTS...
echo =========================================
java -cp "out;lib\*" TestReview2Upgrades
if errorlevel 1 (
    echo Upgrade tests failed.
    pause
    exit /b 1
)
echo.
echo =========================================
echo ALL REVIEW TESTS PASSED SUCCESSFULLY!
echo =========================================
pause
