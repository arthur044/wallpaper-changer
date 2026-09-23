@echo off
rem Duplo clique aqui: gera o APK de release e, com o celular conectado, oferece instalar.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
echo.
pause
