@echo off
setlocal
cd /d "%~dp0"
call env.bat

echo [1/4] Building frontend (Vite)...
pushd frontend
call npm run build
if errorlevel 1 (echo FRONTEND BUILD FAILED & popd & pause & exit /b 1)
popd

echo [2/4] Copying dist into backend static (single-process embed)...
if exist backend\src\main\resources\static rmdir /s /q backend\src\main\resources\static
mkdir backend\src\main\resources\static
xcopy /e /i /y frontend\dist backend\src\main\resources\static >nul

rem A running backend keeps target\*.jar open, so mvn clean cannot delete it.
rem Graceful exit first (it finalizes any recording), then kill whatever is left on :8090.
echo [3/4] Stopping a running backend (it will need restarting afterwards)...
curl -s -X POST http://localhost:8090/api/system/exit >nul 2>&1
timeout /t 4 /nobreak >nul
for /f "tokens=5" %%p in ('netstat -ano ^| findstr :8090 ^| findstr LISTENING') do taskkill /F /PID %%p >nul 2>&1

echo [4/4] Packaging executable jar...
call "%AIQIPIAN_MVN%\bin\mvn.cmd" -f backend\pom.xml clean package -DskipTests
if errorlevel 1 (echo PACKAGE FAILED & pause & exit /b 1)

echo.
echo Done: backend\target\aiqiepian-backend-1.0.0.jar
echo Double-click the Desktop icon "AI QiePian" (or start.bat) to launch.
pause
