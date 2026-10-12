@echo off
setlocal
cd /d "%~dp0backend"
set "JAVA=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin\java.exe"
set "JAR=target\aiqiepian-backend-1.0.0.jar"

if not exist "%JAR%" (
  echo [ERROR] jar not found: %JAR%
  echo Run build-package.bat first.
  pause
  exit /b 1
)

rem Optional: start local Ollama if installed and not already running (for AI title/desc).
set "OLLAMA=%LOCALAPPDATA%\Programs\Ollama\ollama.exe"
if exist "%OLLAMA%" (
  tasklist /FI "IMAGENAME eq ollama.exe" 2>nul | find /i "ollama.exe" >nul || start "Ollama" /min "%OLLAMA%" serve
)

echo Starting AI QiePian (real mode, single process UI+API on :8090)...
start "AI QiePian Backend" "%JAVA%" -jar "%JAR%" --engine.mode=real --server.port=8090 --logging.file.name=run8090.log
timeout /t 4 /nobreak >nul
start "" "http://localhost:8090"
echo Done. Backend runs in its own window. To stop, run stop.bat.
endlocal
