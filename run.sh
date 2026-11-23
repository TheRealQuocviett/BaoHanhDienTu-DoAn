#!/usr/bin/env sh
cd "$(dirname "$0")" || exit 1
[ -f lib/sqlite-jdbc-3.50.3.0.jar ] || sh ./tai-thu-vien.sh
rm -rf bin
javac -encoding UTF-8 -d bin $(find src/main/java -name '*.java') || { echo "Bien dich that bai (can JDK 17+)"; exit 1; }
exec java -Dfile.encoding=UTF-8 -cp "bin:lib/*" vn.baohanh.Main "$@"
