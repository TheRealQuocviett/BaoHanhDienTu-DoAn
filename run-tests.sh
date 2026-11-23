#!/usr/bin/env sh
cd "$(dirname "$0")" || exit 1
[ -f lib/sqlite-jdbc-3.50.3.0.jar ] || sh ./tai-thu-vien.sh
rm -rf bin-test
javac -encoding UTF-8 -d bin-test $(find src -name '*.java') || exit 1
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "bin-test:lib/*" vn.baohanh.test.KiemThu
