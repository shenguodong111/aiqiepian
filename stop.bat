@echo off
echo Stopping AI QiePian backend (graceful exit closes any recording ffmpeg)...
curl -s -X POST http://localhost:8090/api/system/exit >nul 2>&1
timeout /t 4 /nobreak >nul
rem Fallback: force-kill whatever still holds :8090
for /f "tokens=5" %%p in ('netstat -ano ^| findstr :8090 ^| findstr LISTENING') do (
  echo Force killing leftover PID %%p
  taskkill /F /PID %%p >nul 2>&1
)
echo Stopped.
pause
