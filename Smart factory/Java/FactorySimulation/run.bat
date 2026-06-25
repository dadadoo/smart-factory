@echo off
chcp 65001 > nul
cd /d "%~dp0"

echo ==========================================
echo  Smart Factory Collector + API Server
echo ==========================================

REM jar 파일 자동 탐색
set JAR=
for %%f in (*.jar) do set JAR=%%f

if "%JAR%"=="" (
    echo [ERROR] mysql-connector-j-*.jar not found.
    echo         Download from: https://dev.mysql.com/downloads/connector/j/
    echo         Place the .jar file in this folder and run again.
    pause
    exit /b 1
)

echo [INFO] JDBC driver: %JAR%

REM bin 폴더 생성
if not exist bin mkdir bin

REM 컴파일
echo [INFO] Compiling...
javac -encoding UTF-8 -cp ".;%JAR%" src\*.java -d bin
if errorlevel 1 (
    echo [ERROR] Compile failed.
    pause
    exit /b 1
)

REM config 폴더 복사
if not exist bin\config mkdir bin\config
copy /Y config\*.properties bin\config\ > nul

REM 실행
echo [INFO] Starting collector (Ctrl+C to stop)
echo.
java -Dfile.encoding=UTF-8 -cp ".;bin;%JAR%" FactoryCollector
pause
