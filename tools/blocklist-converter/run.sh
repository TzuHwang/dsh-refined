#!/bin/bash
# Compile and run the blocklist converter. Usage: ./run.sh
set -e
cd "$(dirname "$0")"
J=/d/code_library/micraft_mods_dev/tools/jdk-17.0.20.1+1/bin
CPW=$(cat cp.txt)
O=../../reference/OldSource/java/todeploy/millenaire
$J/javac -encoding UTF-8 -cp "$CPW" -d out BlocklistConverter.java
$J/java -Dfile.encoding=UTF-8 -cp "out;$CPW" BlocklistConverter "$(cygpath -w $O/blocklist.txt)" millenaire_mapping.txt blocklist_out.txt unresolved.txt 2>&1 | grep -v '^\[' | tail -5
cp blocklist_out.txt ../../src/main/resources/millenaire/blocklist.txt
