#!/bin/sh
set -e

echo "[*] Uninstalling CC-Switch Agent Hub..."
rm -f "$TAIXU_TOOL_DIR/bin/cc-switch-daemon"
echo "[+] CC-Switch Agent Hub uninstalled."
