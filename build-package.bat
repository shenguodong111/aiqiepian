@echo off
setlocal
cd /d "%~dp0"
call env.bat

echo [1/3] Building frontend (Vite)...
pushd frontend
call npm run build
if errorlevel 1 (echo FRONTEND BUILD FAILED & popd & pause & exit /b 1)
popd

echo [2/3] Copying dist into backend static (single-process embed)...
if exist backend\src\main\resources\static rmdir /s /q backend\src\main\resources\static
mkdir backend\src\main\resources\static
xcopy /e /i /y frontend\dist backend\src\main\resources\static >nul

echo [3/3] Packaging executable jar...
call "%AIQIPIAN_MVN%\bin\mvn.cmd" -f backend\pom.xml clean package -DskipTests
if errorlevel 1 (echo PACKAGE FAILED & pause & exit /b 1)

echo.
echo Done: backend\target\aiqiepian-backend-1.0.0.jar
echo Double-click the Desktop icon "AI QiePian" (or start.bat) to launch.
pause
