#!/usr/bin/env bash
# Offline checks for the map engine. Nothing here ships in the mod jar.
#
#   ./gradlew.bat build --no-daemon && ./gradlew.bat --stop
#   bash devtest/run-tests.sh
#
# Runs from Git Bash on Windows. Uses the jars Gradle already downloaded.
#   pure     region file format, shading maths, shape clipping (no game, no GPU)
#   sampler  chunk sampling against real Chunk storage objects
#   gl       region store + drawing on an offscreen OpenGL surface, compared pixel by pixel
#   visual   renders the real minimap code to a PNG you can look at
set -e
cd "$(dirname "$0")/.."

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
jar_of() { find "$CACHE/$1" -name "$2" 2>/dev/null | head -1; }
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

MC=$(ls -t .gradle/loom-cache/minecraftMaven/net/minecraft/*/*/*-v2.jar 2>/dev/null | head -1)
LWJGL=$(jar_of org.lwjgl.lwjgl/lwjgl "lwjgl-2.9.0.jar")
NATIVES=$(jar_of org.lwjgl.lwjgl/lwjgl-platform "lwjgl-platform-2.9.0-natives-windows.jar")
GSON=$(jar_of com.google.code.gson "gson-2.2.2.jar")
GUAVA=$(jar_of com.google.guava "guava-14.0.1.jar")
LOADER=$(jar_of net.fabricmc/fabric-loader "fabric-loader-0.14.19.jar")
for f in "$MC" "$LWJGL" "$NATIVES" "$GSON" "$GUAVA" "$LOADER"; do
	[ -f "$f" ] || { echo "Missing a dependency jar (run a Gradle build first): '$f'"; exit 2; }
done
[ -d build/classes/java/main ] || { echo "Build the mod first: ./gradlew.bat build --no-daemon"; exit 2; }

OUT=build/devtest
RUN="$OUT/run-$(date +%Y%m%d-%H%M%S)"   # a fresh directory every time; old runs are left alone
mkdir -p "$OUT/classes" "$OUT/natives" "$RUN/pure" "$RUN/gl" "$RUN/visual"
unzip -q -o "$NATIVES" -d "$OUT/natives"

CP="$(win "$PWD/build/classes/java/main");$(win "$PWD/$MC");$(win "$LWJGL");$(win "$GSON");$(win "$GUAVA");$(win "$LOADER")"
javac -nowarn -cp "$CP" -d "$OUT/classes" devtest/src/PureTest.java devtest/src/SamplerTest.java devtest/src/GLTest.java \
	devtest/src/net/fabricmc/example/minimap/VisualTest.java 2>&1 | grep -v "sun.misc.Unsafe\|^Note:\|\^\|warning" || true
CP="$CP;$(win "$PWD/$OUT/classes")"
LIB="-Djava.library.path=$(win "$PWD/$OUT/natives")"

status=0
echo "== pure";    java -cp "$CP" PureTest "$(win "$PWD/$RUN/pure")" || status=1
echo "== sampler"; java -cp "$CP" SamplerTest || status=1
echo "== gl";      java "$LIB" -cp "$CP" GLTest "$(win "$PWD/$RUN/gl")" || status=1
echo "== visual";  java "$LIB" -cp "$CP" net.fabricmc.example.minimap.VisualTest "$(win "$PWD/$RUN/visual")" || status=1

echo
if [ $status -eq 0 ]; then echo "ALL OFFLINE CHECKS PASSED  (picture: $RUN/visual/minimaps.png)"; else echo "SOME CHECKS FAILED"; fi
exit $status
