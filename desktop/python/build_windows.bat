@echo off
setlocal
cd /d "%~dp0"
echo === ViewCast Viewer : construction de l'executable Windows ===

if "%PYTHON%"=="" (
    python --version >nul 2>&1
    if errorlevel 1 (
        if exist "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" (
            set "PYTHON=%LOCALAPPDATA%\Programs\Python\Python311\python.exe"
        ) else if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" (
            set "PYTHON=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
        ) else (
            set "PYTHON=py"
        )
    ) else (
        set "PYTHON=python"
    )
)

"%PYTHON%" -m pip install --upgrade pip
"%PYTHON%" -m pip install -r requirements.txt || goto :error
"%PYTHON%" -m PyInstaller --noconfirm --clean --onefile --windowed --name ViewCastViewer --icon "..\windows\icon.ico" viewcast_viewer.py || goto :error
echo.
echo OK : dist\ViewCastViewer.exe
exit /b 0

:error
echo ECHEC de la construction.
exit /b 1
