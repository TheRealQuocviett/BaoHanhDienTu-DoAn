#!/usr/bin/env sh
# Tai driver SQLite JDBC va thu vien log (SLF4J) vao thu muc lib/
cd "$(dirname "$0")" || exit 1
mkdir -p lib
M=https://repo1.maven.org/maven2
get() {
  if [ -f "lib/$2" ]; then echo "Da co $2"; return; fi
  echo "Dang tai $2 ..."
  curl -fsSL -o "lib/$2" "$M/$1" || { rm -f "lib/$2"; echo "Loi tai $2"; }
}
get org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar sqlite-jdbc-3.50.3.0.jar
get org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar slf4j-api-1.7.36.jar
get org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar slf4j-nop-1.7.36.jar
