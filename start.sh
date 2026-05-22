#!/bin/bash
echo ""
echo " ╔══════════════════════════════════════════════╗"
echo " ║    FlexCity Config Manager  -  Java          ║"
echo " ╚══════════════════════════════════════════════╝"
echo ""

# Java kontrolü
if ! command -v java &>/dev/null; then
    echo " [HATA] Java bulunamadı. JDK 17+ yükleyin."
    exit 1
fi

# Maven kontrolü
if ! command -v mvn &>/dev/null; then
    echo " [HATA] Maven bulunamadı."
    exit 1
fi

echo " [1/2] Derleniyor..."
mvn package -q -DskipTests || { echo "[HATA] Derleme başarısız!"; exit 1; }

echo " [2/2] Başlatılıyor..."
echo " ► http://localhost:5050"
echo ""
java -jar target/config-manager-1.0.0.jar
