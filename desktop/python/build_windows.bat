@echo off
setlocal
cd /d "%~dp0"
echo === ViewCast Viewer : construction de l'executable Windows ===
python -m pip install --upgrade pip
python -m pip install -r requirements.txt || goto :error
python -m PyInstaller --noconfirm --clean --onefile --windowed --collect-all av --collect-all sounddevice --add-data "..\windows\icon.ico;." --name ViewCastViewer --icon "..\windows\icon.ico" viewcast_viewer.py || goto :error
echo.
echo OK : dist\ViewCastViewer.exe
exit /b 0

:error
echo ECHEC de la construction.
exit /b 1
