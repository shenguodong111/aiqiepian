@echo off
setlocal
set "JAVA=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin\javaw.exe"
set "BACKEND=C:\Users\25436\Documents\Qoder\2026-10-09\ab87a17e\backend"
set "JAR=%BACKEND%\target\aiqiepian-backend-1.0.0.jar"
set "URL=http://localhost:8090"

rem If already running, just open the window (no second backend).
curl -s -o nul "%URL%/api/settings" && goto open

if not exist "%JAR%" goto nojar

pushd "%BACKEND%"
start "" "%JAVA%" -jar "%JAR%" --engine.mode=real --server.port=8090 --logging.file.name=run8090.log
popd

set /a n=0
:wait
set /a n+=1
timeout /t 2 /nobreak >nul
curl -s -o nul "%URL%/api/settings" && goto open
if %n% lss 20 goto wait

:open
set "CHROME=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
set "CHROME86=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
set "EDGE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if exist "%CHROME%"   ( start "" "%CHROME%"   --app="%URL%" --window-size=1280,860 & goto end )
if exist "%CHROME86%" ( start "" "%CHROME86%" --app="%URL%" --window-size=1280,860 & goto end )
if exist "%EDGE%"     ( start "" "%EDGE%"     --app="%URL%" --window-size=1280,860 & goto end )
start "" "%URL%"
goto end

:nojar
echo Backend jar not found. Run build-package.bat first, then reopen.
timeout /t 8 >nul

:end
endlocal
