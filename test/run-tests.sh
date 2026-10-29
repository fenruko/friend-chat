#!/usr/bin/env bash
# Runs all Minecraft-free tests (needs only a JDK 17+). The Minecraft/Concentus glue lives in friendchat.client and is
# excluded here, exactly like PixelTunes keeps its core testable without the game.
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d)
javac -d "$out" $(find src/client/java/friendchat/core test/friendchat/core -name '*.java')
for t in CryptoTests ChatTests UiTests; do
  [ -f "test/friendchat/core/$t.java" ] && java -cp "$out" friendchat.core.$t
done
true
