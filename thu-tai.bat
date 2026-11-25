@echo off
chcp 65001 >nul
rem ===== THU TAI: tao 50.000 phieu gia tren CSDL tam va do toc do =====
rem Cach dung:  thu-tai.bat           (50.000 phieu)
rem             thu-tai.bat 100000    (so phieu tuy chon)
rem Ket qua ghi vao docs\thu-tai-ket-qua.md (dan vao bao cao do an). Khong dong toi du lieu that trong data\.
cd /d "%~dp0"
if not exist lib\sqlite-jdbc-3.50.3.0.jar call tai-thu-vien.bat nopause
if exist bin-tai rmdir /s /q bin-tai
set S=src\main\java\vn\baohanh
javac -encoding UTF-8 -nowarn -d bin-tai %S%\*.java %S%\api\*.java %S%\db\*.java %S%\model\*.java %S%\notify\*.java %S%\print\*.java %S%\security\*.java %S%\service\*.java %S%\ui\*.java %S%\util\*.java %S%\tools\*.java
if errorlevel 1 (
  echo Bien dich that bai. Kiem tra da cai JDK 17 tro len chua: javac -version
  pause
  exit /b 1
)
java -Xmx1g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "bin-tai;lib\*" vn.baohanh.tools.ThuTai %*
rmdir /s /q bin-tai
echo.
echo Mo file docs\thu-tai-ket-qua.md de xem bang ket qua.
pause
