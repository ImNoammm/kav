#!/usr/bin/env bash
# Signs recipe/kav.json with the key in .signing/ (never committed), so every installed Kav takes it within a day.
# Raise "version" in the file first: Kav only takes a recipe newer than the one it has.
set -euo pipefail
cd "$(dirname "$0")/.."
key=.signing/recipe.key
[ -f "$key" ] || { echo "No signing key at $key" >&2; exit 1; }
python3 -c 'import json,sys; json.load(open("recipe/kav.json"))' || { echo "recipe/kav.json is not valid JSON" >&2; exit 1; }
openssl dgst -sha256 -sign "$key" recipe/kav.json | base64 -w0 > recipe/kav.json.sig
openssl ec -in "$key" -pubout 2>/dev/null > /tmp/kav-recipe-pub.pem
base64 -d recipe/kav.json.sig | openssl dgst -sha256 -verify /tmp/kav-recipe-pub.pem -signature /dev/stdin recipe/kav.json
echo "Signed version $(python3 -c 'import json; print(json.load(open("recipe/kav.json"))["version"])'). Commit and push recipe/ to publish it."
