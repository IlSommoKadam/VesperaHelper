@echo off
rem Build debug + installa Vespera Helper sul Pi via ADB. Log in tools\build-install-pi.log
setlocal
cd /d "%~dp0.."
set "LOG=%~dp0build-install-pi.log"
if "%PI%"=="" set "PI=192.168.1.4:5555"
set "SDK=C:\Users\cad\AppData\Local\Android\Sdk"
if not exist local.properties echo sdk.dir=C\:\\Users\\cad\\AppData\\Local\\Android\\Sdk> local.properties
if "%JAVA_HOME%"=="" if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ADB=%SDK%\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
echo === %DATE% %TIME% build+install su %PI% === > "%LOG%"
if not exist secrets.properties (echo MANCA secrets.properties: le chiavi Ed25519 sarebbero finte, build annullata >> "%LOG%" & goto end)
call gradlew.bat assembleDebug >> "%LOG%" 2>&1
if errorlevel 1 (echo BUILD FALLITA >> "%LOG%" & goto end)
"%ADB%" connect %PI% >> "%LOG%" 2>&1
"%ADB%" -s %PI% install -r app\build\outputs\apk\debug\app-debug.apk >> "%LOG%" 2>&1
if errorlevel 1 (echo INSTALL FALLITA >> "%LOG%" & goto end)
"%ADB%" -s %PI% shell dumpsys package com.vaonis.vesperahelper | findstr /i "versionName versionCode" >> "%LOG%" 2>&1
"%ADB%" -s %PI% shell monkey -p com.vaonis.vesperahelper -c android.intent.category.LAUNCHER 1 >> "%LOG%" 2>&1
echo OK >> "%LOG%"
:end
type "%LOG%"
timeout /t 15
