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
#   heads    cuts every mob face out of the game's skins with the radar's table
#   icons    draws the model-based horse icons from the game's textures (GPU)
#   faces    straight-on model icons (snouts, beaks, noses, horns) and clipping to the round minimap (GPU)
#   labels   where in-world waypoint labels land on the HUD, from a real camera setup (GPU)
#   export   map export to PNG: region files + unsaved chunks, checked pixel by pixel, and scaled down
#   screens  renders every Minimap CE screen with the game's real font (needs run/config/mcpatcher.conf,
#            which the first runClient creates)
set -e
cd "$(dirname "$0")/.."

CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
jar_of() { find "$CACHE/$1" -name "$2" ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | sort | tail -1; }
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

MC=$(ls -t .gradle/loom-cache/minecraftMaven/net/minecraft/*/*/*-v2.jar 2>/dev/null | head -1)
LWJGL=$(jar_of org.lwjgl.lwjgl/lwjgl "lwjgl-2.9.0.jar")
NATIVES=$(jar_of org.lwjgl.lwjgl/lwjgl-platform "lwjgl-platform-2.9.0-natives-windows.jar")
GSON=$(jar_of com.google.code.gson "gson-2.2.2.jar")
GUAVA=$(jar_of com.google.guava "guava-14.0.1.jar")
LOADER=$(jar_of net.fabricmc/fabric-loader "fabric-loader-0.14.19.jar")
# Libraries the game's own resource and texture code needs for the screens suite.
LANG3=$(jar_of org.apache.commons "commons-lang3-3.1.jar")
CIO=$(find "$CACHE/commons-io" -name "commons-io-2.*.jar" ! -name "*sources*" 2>/dev/null | head -1)
TYPESAFE=$(jar_of com.typesafe "config-1.4.5.jar")
AW=$(jar_of net.fabricmc "access-widener-2*.jar")
for f in "$MC" "$LWJGL" "$NATIVES" "$GSON" "$GUAVA" "$LOADER"; do
	[ -f "$f" ] || { echo "Missing a dependency jar (run a Gradle build first): '$f'"; exit 2; }
done
[ -d build/classes/java/main ] || { echo "Build the mod first: ./gradlew.bat build --no-daemon"; exit 2; }

OUT=build/devtest
RUN="$OUT/run-$(date +%Y%m%d-%H%M%S)"   # a fresh directory every time; old runs are left alone
mkdir -p "$OUT/classes" "$OUT/natives" "$RUN/pure" "$RUN/gl" "$RUN/visual"
unzip -q -o "$NATIVES" -d "$OUT/natives"

CP="$(win "$PWD/build/classes/java/main");$(win "$PWD/$MC");$(win "$LWJGL");$(win "$GSON");$(win "$GUAVA");$(win "$LOADER")"
for f in "$LANG3" "$CIO" "$TYPESAFE" "$AW"; do [ -f "$f" ] && CP="$CP;$(win "$f")"; done
javac -nowarn -cp "$CP" -d "$OUT/classes" devtest/src/PureTest.java devtest/src/SamplerTest.java devtest/src/GLTest.java \
	devtest/src/RealMapView.java devtest/src/net/fabricmc/example/minimap/VisualTest.java \
	devtest/src/net/fabricmc/example/minimap/HeadSheet.java devtest/src/net/fabricmc/example/minimap/VisualTestTerrain.java devtest/src/net/fabricmc/example/minimap/GuiShots.java \
	devtest/src/net/fabricmc/example/minimap/ModelIconTest.java devtest/src/net/fabricmc/example/minimap/FrontIconTest.java \
	devtest/src/net/fabricmc/example/minimap/WaypointLabelTest.java devtest/src/net/fabricmc/example/minimap/engine/ExportTest.java \
	2>&1 | grep -v "sun.misc.Unsafe\|^Note:\|\^\|warning" || true
CP="$CP;$(win "$PWD/$OUT/classes")"
LIB="-Djava.library.path=$(win "$PWD/$OUT/natives")"

status=0
echo "== pure";    java -cp "$CP" PureTest "$(win "$PWD/$RUN/pure")" || status=1
echo "== sampler"; java -cp "$CP" SamplerTest || status=1
echo "== gl";      java "$LIB" -cp "$CP" GLTest "$(win "$PWD/$RUN/gl")" || status=1
echo "== visual";  java "$LIB" -cp "$CP" net.fabricmc.example.minimap.VisualTest "$(win "$PWD/$RUN/visual")" || status=1
echo "== heads";   java -cp "$CP" net.fabricmc.example.minimap.HeadSheet "$(win "$PWD/$MC")" "$(win "$PWD/$RUN/visual/heads.png")" || status=1
echo "== icons";   java "$LIB" -cp "$CP" net.fabricmc.example.minimap.ModelIconTest "$(win "$PWD/$MC")" "$(win "$PWD/$RUN/visual/model-icons.png")" || status=1
echo "== faces";   java "$LIB" -cp "$CP" net.fabricmc.example.minimap.FrontIconTest "$(win "$PWD/$MC")" "$(win "$PWD/$RUN/visual/front-icons.png")" || status=1
echo "== labels";  java "$LIB" -cp "$CP" net.fabricmc.example.minimap.WaypointLabelTest || status=1
echo "== export";  java -cp "$CP" net.fabricmc.example.minimap.engine.ExportTest "$(win "$PWD/$RUN/visual")" || status=1
if [ -f run/config/mcpatcher.conf ]; then
	echo "== screens"
	mkdir -p "$RUN/screens/game/config"
	cp run/config/mcpatcher.conf "$RUN/screens/game/config/"
	java "$LIB" -cp "$CP" net.fabricmc.example.minimap.GuiShots "$(win "$PWD/$RUN/screens")" 2>&1 | grep -v "^\[\|INFO\|WARNING\|Launcher profile\|^$"
	[ "${PIPESTATUS[0]}" -eq 0 ] || { echo "SCREENS FAILED"; status=1; }
else
	echo "== screens: skipped (run the game once with runClient so run/config/mcpatcher.conf exists)"
fi

echo
if [ $status -eq 0 ]; then echo "ALL OFFLINE CHECKS PASSED  (picture: $RUN/visual/minimaps.png)"; else echo "SOME CHECKS FAILED"; fi
exit $status
