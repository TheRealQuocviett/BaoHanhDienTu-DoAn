#!/usr/bin/env sh
# THỬ TẢI: tạo 50.000 phiếu giả trên CSDL tạm và đo tốc độ. Dùng: sh thu-tai.sh [số phiếu]
# Kết quả ghi vào docs/thu-tai-ket-qua.md. Không đụng tới dữ liệu thật trong data/.
cd "$(dirname "$0")" || exit 1
[ -f lib/sqlite-jdbc-3.50.3.0.jar ] || sh ./tai-thu-vien.sh
rm -rf bin-tai
javac -encoding UTF-8 -nowarn -d bin-tai $(find src/main/java -name '*.java') || { echo "Bien dich that bai (can JDK 17+)"; exit 1; }
java -Xmx1g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "bin-tai:lib/*" vn.baohanh.tools.ThuTai "$@"
status=$?
rm -rf bin-tai
exit $status
