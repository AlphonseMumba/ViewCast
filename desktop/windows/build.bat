@echo off
setlocal
call "%~dp0..\python\build_windows.bat" || exit /b 1
if not exist "%~dp0dist" mkdir "%~dp0dist"
copy /Y "%~dp0..\python\dist\ViewCastViewer.exe" "%~dp0dist\ViewCastViewer.exe"
echo Executable copie dans desktop\windows\dist\ViewCastViewer.exe
