@echo off
setlocal
cd /d "%~dp0"
call env.bat
cd backend
set "JAR=target\aiqiepian-backend-1.0.0.jar"

if not exist "%JAR%" (
  echo [ERROR] jar not found: %JAR%
  echo Run build-package.bat first.
  pause
  exit /b 1
)

rem Optional: start local Ollama if installed and not already running (for AI title/desc).
rem Prefer the portable build, fall back to the installer location.
set "OLLAMA=%AIQIPIAN_OLLAMA%"
if not exist "%OLLAMA%" set "OLLAMA=%LOCALAPPDATA%\Programs\Ollama\ollama.exe"
if exist "%OLLAMA%" (
  if not defined OLLAMA_MODELS if exist "%AIQIPIAN_OLLAMA_MODELS%" set "OLLAMA_MODELS=%AIQIPIAN_OLLAMA_MODELS%"
  tasklist /FI "IMAGENAME eq ollama.exe" 2>nul | find /i "ollama.exe" >nul || start "Ollama" /min "%OLLAMA%" serve
)

echo Starting AI QiePian (real mode, single process UI+API on :8090)...
start "AI QiePian Backend" "%JAVA_HOME%\bin\java.exe" -jar "%JAR%" --engine.mode=real --server.port=8090 --logging.file.name=run8090.log
timeout /t 4 /nobreak >nul
start "" "http://localhost:8090"
echo Done. Backend runs in its own window. To stop, run stop.bat.
endlocal
