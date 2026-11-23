@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist lib\sqlite-jdbc-3.50.3.0.jar call tai-thu-vien.bat nopause
if exist bin-test rmdir /s /q bin-test
set S=src\main\java\vn\baohanh
javac -encoding UTF-8 -d bin-test %S%\*.java %S%\api\*.java %S%\db\*.java %S%\model\*.java %S%\notify\*.java %S%\print\*.java %S%\security\*.java %S%\service\*.java %S%\ui\*.java %S%\util\*.java src\test\java\vn\baohanh\test\*.java
if errorlevel 1 (
  echo Bien dich that bai.
  pause
  exit /b 1
)
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "bin-test;lib\*" vn.baohanh.test.KiemThu
pause
