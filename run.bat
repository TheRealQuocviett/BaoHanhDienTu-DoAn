@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist lib\sqlite-jdbc-3.50.3.0.jar call tai-thu-vien.bat nopause
if exist bin rmdir /s /q bin
set S=src\main\java\vn\baohanh
javac -encoding UTF-8 -d bin %S%\*.java %S%\api\*.java %S%\db\*.java %S%\model\*.java %S%\notify\*.java %S%\print\*.java %S%\security\*.java %S%\service\*.java %S%\ui\*.java %S%\util\*.java
if errorlevel 1 (
  echo Bien dich that bai. Kiem tra da cai JDK 17 tro len chua: javac -version
  pause
  exit /b 1
)
java -Dfile.encoding=UTF-8 -cp "bin;lib\*" vn.baohanh.Main %*
pause
