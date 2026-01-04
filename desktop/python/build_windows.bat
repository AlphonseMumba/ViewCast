@echo off
echo Building ViewCast Viewer for Windows...
pip install -r requirements.txt
pyinstaller --onefile --windowed viewcast_viewer.py --name ViewCastViewer
echo Build complete. Executable in dist\ViewCastViewer.exe