#!/usr/bin/env bash
# Runs the caption logic tests (VideoSwitch.kt: language picking, speech chunking, swipes,
# leftover words, line splitting) and TranslationText.kt (tidying text around translation). Plain Kotlin, no phone or Android needed: just kotlinc and java.
# Run from anywhere:  ClipCaptions/tools/logic-tests/run.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
app="$here/../../app/src/main/java/com/clipcaptions/app"
src=("$app/VideoSwitch.kt" "$app/TranslationText.kt")
out=$(mktemp -d)
for test in "$here"/*Test.kt; do
  name=$(basename "$test" .kt)
  kotlinc -nowarn "$test" "${src[@]}" -include-runtime -d "$out/$name.jar" 2>/dev/null
  echo "== $name"
  java -jar "$out/$name.jar"
done
rm -rf "$out"
