#!/usr/bin/env bash
set -euo pipefail

volume="${1:-/Volumes/CIRCUITPY}"
macro_source="${2:-circuitpython/macro_p30_mouse_diagnostic.json}"
boot_source="${3:-circuitpython/boot.py}"

if [[ ! -d "${volume}" ]]; then
  echo "CircuitPython volume not found: ${volume}" >&2
  echo "Mount the Pico as CIRCUITPY, or pass the mounted path as the first argument." >&2
  exit 1
fi

if [[ ! -f "${macro_source}" ]]; then
  echo "Macro file not found: ${macro_source}" >&2
  exit 1
fi

if [[ ! -f "${boot_source}" ]]; then
  echo "Boot file not found: ${boot_source}" >&2
  exit 1
fi

cp "${boot_source}" "${volume}/boot.py"
cp circuitpython/code.py "${volume}/code.py"
cp circuitpython/touch_hid.py "${volume}/touch_hid.py"
cp circuitpython/mouse_hid.py "${volume}/mouse_hid.py"

python3 - "${macro_source}" "${volume}/macro.json" <<'PY'
import json
import sys

source, target = sys.argv[1], sys.argv[2]
with open(source, "r") as fp:
    config = json.load(fp)

config["enabled"] = True

with open(target, "w") as fp:
    json.dump(config, fp, indent=2)
    fp.write("\n")
PY

sync
echo "Installed CircuitPython files to ${volume}"
echo "Boot file: ${boot_source}"
echo "Macro enabled from ${macro_source}"
