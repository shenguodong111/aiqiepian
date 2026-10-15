@echo off
rem Toolchain locations for this machine (JDK / Maven / ffmpeg / Ollama).
rem Shared by start.bat, build-package.bat, launch-app.bat.
rem Keep this file ASCII-only: cmd.exe reads .bat as GBK, Chinese comments break parsing.
rem
rem How a value is decided:
rem   1. If AIQIPIAN_JDK / AIQIPIAN_MVN / AIQIPIAN_FFMPEG is already set in the
rem      environment, it wins (use this to pin a path on an odd machine).
rem   2. Otherwise the first candidate folder that really exists is used, so the
rem      same file works on every machine without editing it.

if not defined AIQIPIAN_JDK (
  call :pick_dir AIQIPIAN_JDK "D:\code\tools\jdk*" "bin\javaw.exe"
  call :pick_dir AIQIPIAN_JDK "C:\Program Files\Microsoft\jdk*" "bin\javaw.exe"
  call :pick_dir AIQIPIAN_JDK "C:\Program Files\Java\jdk*" "bin\javaw.exe"
  call :pick_dir AIQIPIAN_JDK "%USERPROFILE%\tools\jdk*" "bin\javaw.exe"
)
if not defined AIQIPIAN_MVN (
  call :pick_dir AIQIPIAN_MVN "D:\code\tools\apache-maven*" "bin\mvn.cmd"
  call :pick_dir AIQIPIAN_MVN "%USERPROFILE%\tools\maven-home\apache-maven*" "bin\mvn.cmd"
  call :pick_dir AIQIPIAN_MVN "%USERPROFILE%\tools\apache-maven*" "bin\mvn.cmd"
  call :pick_dir AIQIPIAN_MVN "C:\tools\apache-maven*" "bin\mvn.cmd"
)
if not defined AIQIPIAN_FFMPEG (
  call :pick_dir AIQIPIAN_FFMPEG "D:\code\tools\ffmpeg\bin" "ffmpeg.exe"
  call :pick_dir AIQIPIAN_FFMPEG "%USERPROFILE%\tools\ffmpeg\bin" "ffmpeg.exe"
  call :pick_dir AIQIPIAN_FFMPEG "C:\ffmpeg\bin" "ffmpeg.exe"
  rem Last resort: whatever ffmpeg is already on PATH.
  if not defined AIQIPIAN_FFMPEG for /f "delims=" %%f in ('where ffmpeg 2^>nul') do if not defined AIQIPIAN_FFMPEG call :strip_bin AIQIPIAN_FFMPEG "%%~dpf"
)
if not defined AIQIPIAN_OLLAMA set "AIQIPIAN_OLLAMA=D:\code\tools\ollama\ollama.exe"
if not exist "%AIQIPIAN_OLLAMA%" if exist "%LOCALAPPDATA%\Programs\Ollama\ollama.exe" set "AIQIPIAN_OLLAMA=%LOCALAPPDATA%\Programs\Ollama\ollama.exe"
if not defined AIQIPIAN_OLLAMA_MODELS set "AIQIPIAN_OLLAMA_MODELS=D:\code\tools\ollama\models"
if not exist "%AIQIPIAN_OLLAMA_MODELS%" set "AIQIPIAN_OLLAMA_MODELS="

set "JAVA_HOME=%AIQIPIAN_JDK%"
if defined AIQIPIAN_MVN set "MAVEN_HOME=%AIQIPIAN_MVN%"
set "PATH=%AIQIPIAN_FFMPEG%;%PATH%"

if not exist "%JAVA_HOME%\bin\javaw.exe" echo [env.bat] WARNING: no JDK found. Install JDK 17 or set AIQIPIAN_JDK, then retry.
if not exist "%AIQIPIAN_FFMPEG%\ffmpeg.exe" echo [env.bat] WARNING: no ffmpeg found. Recording and rendering will fail until it is installed.
goto :eof

rem ---- helpers ----
:pick_dir
rem %1 = name of the variable to fill, %2 = glob of candidate folders,
rem %3 = marker file that must exist inside the chosen folder.
if defined %~1 goto :eof
for /d %%d in (%2) do if not defined %~1 if exist "%%~fd\%~3" set "%~1=%%~fd"
goto :eof

:strip_bin
rem %1 = variable, %2 = a dir path that may end with a backslash
set "_PICKED=%~2"
if "%_PICKED:~-1%"=="\" set "_PICKED=%_PICKED:~0,-1%"
set "%~1=%_PICKED%"
set "_PICKED="
goto :eof
