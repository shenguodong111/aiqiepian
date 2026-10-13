@echo off
rem Toolchain locations for this machine (JDK / Maven / ffmpeg).
rem Shared by start.bat, build-package.bat, launch-app.bat.
rem Keep this file ASCII-only: cmd.exe reads .bat as GBK, Chinese comments break parsing.
rem Override on another machine by setting these vars before calling, or edit the paths here.
if not defined AIQIPIAN_JDK    set "AIQIPIAN_JDK=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
if not defined AIQIPIAN_MVN    set "AIQIPIAN_MVN=C:\Users\25436\tools\maven-home\apache-maven-3.9.16"
if not defined AIQIPIAN_FFMPEG set "AIQIPIAN_FFMPEG=D:\code\tools\ffmpeg\bin"
rem Portable Ollama (local LLM for AI titles/desc). Installer version is found automatically in start.bat.
if not defined AIQIPIAN_OLLAMA set "AIQIPIAN_OLLAMA=D:\code\tools\ollama\ollama.exe"
if not defined AIQIPIAN_OLLAMA_MODELS set "AIQIPIAN_OLLAMA_MODELS=D:\code\tools\ollama\models"

set "JAVA_HOME=%AIQIPIAN_JDK%"
set "PATH=%AIQIPIAN_FFMPEG%;%PATH%"
