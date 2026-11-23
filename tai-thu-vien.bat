@echo off
rem Tai driver SQLite JDBC va thu vien log (SLF4J) vao thu muc lib\
chcp 65001 >nul
cd /d "%~dp0"
if not exist lib mkdir lib
set M=https://repo1.maven.org/maven2
call :get org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar sqlite-jdbc-3.50.3.0.jar
call :get org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar slf4j-api-1.7.36.jar
call :get org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar slf4j-nop-1.7.36.jar
if exist lib\sqlite-jdbc-3.50.3.0.jar (
  echo.
  echo Da co du thu vien trong thu muc lib\
) else (
  echo.
  echo KHONG TAI DUOC. Hay tai thu cong 3 file tren tu trang:
  echo   %M%/org/xerial/sqlite-jdbc/3.50.3.0/
  echo   %M%/org/slf4j/slf4j-api/1.7.36/
  echo   %M%/org/slf4j/slf4j-nop/1.7.36/
  echo roi chep vao thu muc lib\
)
if not "%1"=="nopause" pause
exit /b 0

:get
if exist "lib\%2" (
  echo Da co %2
  exit /b 0
)
echo Dang tai %2 ...
curl -fsSL -o "lib\%2" "%M%/%1"
if not exist "lib\%2" powershell -NoProfile -Command "Invoke-WebRequest -Uri '%M%/%1' -OutFile 'lib\%2'"
exit /b 0
