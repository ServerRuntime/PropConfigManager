@echo off
chcp 65001 >nul
echo.
echo  ╔══════════════════════════════════════════════╗
echo  ║    FlexCity Config Manager  -  Java          ║
echo  ╚══════════════════════════════════════════════╝
echo.

:: ── Java kontrolü ──────────────────────────────────
java -version >nul 2>&1
if errorlevel 1 (
    echo  [HATA] Java bulunamadi. JDK 17+ yukleyin:
    echo         https://adoptium.net
    pause & exit /b 1
)

:: ── Maven kontrolü ─────────────────────────────────
mvn -v >nul 2>&1
if errorlevel 1 (
    echo  [HATA] Maven bulunamadi. Lutfen Maven yukleyin:
    echo         https://maven.apache.org/download.cgi
    echo.
    echo  Ya da JAVA_HOME ve PATH'e Maven ekleyin.
    pause & exit /b 1
)

echo  [1/2] Derleniyor (ilk seferde 1-2 dk surabilir)...
call mvn package -q -DskipTests
if errorlevel 1 (
    echo  [HATA] Derleme basarisiz!
    pause & exit /b 1
)

echo  [2/2] Uygulama baslatiliyor...
echo.
echo  ► Tarayicinizda acin : http://localhost:5050
echo  ► Durdurmak icin     : Ctrl+C
echo.

java -jar target\config-manager-1.0.0.jar

pause
