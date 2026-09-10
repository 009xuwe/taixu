#!/bin/sh
set -e

echo "[*] Installing CC-Switch Agent Hub..."

TOOL_DIR="${TAIXU_TOOL_DIR:-/opt/taixu/tools/cc-switch}"
DATA_DIR="${TAIXU_TOOL_DATA:-$TOOL_DIR/data}"
PAYLOAD="${TAIXU_PLUGIN_PAYLOAD:-/opt/taixu/imports/cc-switch/payload}"

mkdir -p "$TOOL_DIR/bin"
mkdir -p "$TOOL_DIR/lib"
mkdir -p "$DATA_DIR" 2>/dev/null || true

# 1. Copy the server ELF binary to lib/cc-switch-server
if [ -f "$PAYLOAD/lib/cc-switch-server" ]; then
    cp -a "$PAYLOAD/lib/cc-switch-server" "$TOOL_DIR/lib/cc-switch-server"
    chmod 755 "$TOOL_DIR/lib/cc-switch-server"
elif [ -f "$PAYLOAD/bin/cc-switch-daemon" ] && [ "$(head -c 4 "$PAYLOAD/bin/cc-switch-daemon" 2>/dev/null)" = "$(printf '\177ELF')" ]; then
    cp -a "$PAYLOAD/bin/cc-switch-daemon" "$TOOL_DIR/lib/cc-switch-server"
    chmod 755 "$TOOL_DIR/lib/cc-switch-server"
fi

# 2. Copy wrapper script to bin/cc-switch-daemon
if [ -f "$PAYLOAD/bin/cc-switch-daemon" ] && [ "$(head -c 4 "$PAYLOAD/bin/cc-switch-daemon" 2>/dev/null)" != "$(printf '\177ELF')" ]; then
    cp -a "$PAYLOAD/bin/cc-switch-daemon" "$TOOL_DIR/bin/cc-switch-daemon"
else
    cat << 'EOF' > "$TOOL_DIR/bin/cc-switch-daemon"
#!/bin/sh
set -e

PORT="${CC_SWITCH_PORT:-19870}"
DATA_DIR="${CC_SWITCH_DATA_DIR:-/opt/taixu/data/cc-switch}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_BIN="$TOOL_DIR/lib/cc-switch-server"

for arg in "$@"; do
    case "$arg" in
        --version|-v|-V)
            echo "cc-switch 1.0.0 (ARM64)"
            exit 0
            ;;
        --help|-h)
            echo "Usage: cc-switch-daemon [--port <port>] [--data-dir <dir>]"
            exit 0
            ;;
    esac
done

while [ $# -gt 0 ]; do
    case "$1" in
        --port|-p)
            PORT="$2"
            shift 2
            ;;
        --data-dir|-d)
            DATA_DIR="$2"
            shift 2
            ;;
        *)
            shift
            ;;
    esac
done

mkdir -p "$DATA_DIR"
export HOST="${CC_SWITCH_HOST:-0.0.0.0}"
export CC_SWITCH_HOST="${CC_SWITCH_HOST:-0.0.0.0}"
export PORT="$PORT"
export CC_SWITCH_PORT="$PORT"
export CC_SWITCH_DATA_DIR="$DATA_DIR"
export XDG_DATA_HOME="$DATA_DIR"
export CC_SWITCH_LAN_CORS=1

if [ -x "$SERVER_BIN" ] || [ -f "$SERVER_BIN" ]; then
    chmod 755 "$SERVER_BIN" 2>/dev/null || true
    exec "$SERVER_BIN"
elif [ -x "$TOOL_DIR/bin/cc-switch-server" ] || [ -f "$TOOL_DIR/bin/cc-switch-server" ]; then
    chmod 755 "$TOOL_DIR/bin/cc-switch-server" 2>/dev/null || true
    exec "$TOOL_DIR/bin/cc-switch-server"
else
    echo "Error: cc-switch-server ELF binary not found at $SERVER_BIN" >&2
    exit 1
fi
EOF
fi

chmod 755 "$TOOL_DIR/bin/cc-switch-daemon"

echo "[+] CC-Switch Agent Hub installed successfully!"
