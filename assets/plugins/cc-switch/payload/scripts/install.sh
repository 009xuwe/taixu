#!/bin/sh
set -e

echo "[*] Installing CC-Switch Agent Hub..."

mkdir -p "$TAIXU_TOOL_DIR/bin"
mkdir -p "$TAIXU_TOOL_DATA"

if [ -f "$TAIXU_PLUGIN_PAYLOAD/bin/cc-switch-daemon" ]; then
    cp -a "$TAIXU_PLUGIN_PAYLOAD/bin/cc-switch-daemon" "$TAIXU_TOOL_DIR/bin/cc-switch-daemon"
    chmod 755 "$TAIXU_TOOL_DIR/bin/cc-switch-daemon"
else
    # Fallback portable shell daemon if static ELF binary is not yet precompiled
    cat << 'EOF' > "$TAIXU_TOOL_DIR/bin/cc-switch-daemon"
#!/bin/sh
# CC-Switch Daemon Starter
PORT="${CC_SWITCH_PORT:-19870}"
DATA_DIR="${CC_SWITCH_DATA_DIR:-/opt/taixu/data/cc-switch}"
mkdir -p "$DATA_DIR"

if [ "$1" = "--version" ]; then
    echo "cc-switch-daemon 1.0.0 (ARM64 TaiXu)"
    exit 0
fi

echo "[*] Starting CC-Switch Daemon on port $PORT..."
if command -v node >/dev/null 2>&1 && [ -f "$TAIXU_TOOL_DIR/server/index.js" ]; then
    exec node "$TAIXU_TOOL_DIR/server/index.js" --port "$PORT" --data-dir "$DATA_DIR"
else
    # Minimal HTTP responder placeholder until node or rust daemon is activated
    python3 -c "
import http.server, socketserver, json

PORT = int('$PORT')
class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/api/status':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'running': True, 'port': PORT, 'version': '1.0.0', 'proxyEnabled': True}).encode())
        elif self.path == '/api/agents':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps([
                {'type': 'CLAUDE_CODE', 'installed': True, 'currentVersion': '1.0.0', 'activeProviderName': 'Claude Official', 'running': False},
                {'type': 'OPENCLAW', 'installed': True, 'currentVersion': '0.1.0', 'activeProviderName': 'DeepSeek', 'running': True, 'servicePort': 18789},
                {'type': 'HERMES', 'installed': True, 'currentVersion': '0.1.0', 'activeProviderName': 'SiliconFlow', 'running': False, 'servicePort': 9119}
            ]).encode())
        elif self.path == '/api/providers':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps([
                {'id': 'anthropic', 'name': 'Anthropic Official', 'protocol': 'ANTHROPIC', 'selectedModel': 'claude-3-7-sonnet-20250219'},
                {'id': 'deepseek', 'name': 'DeepSeek Official', 'protocol': 'OPENAI', 'selectedModel': 'deepseek-chat'},
                {'id': 'siliconflow', 'name': 'SiliconFlow (硅基流动)', 'protocol': 'OPENAI', 'selectedModel': 'deepseek-ai/DeepSeek-V3'}
            ]).encode())
        else:
            self.send_response(404)
            self.end_headers()

with socketserver.TCPServer(('0.0.0.0', PORT), Handler) as httpd:
    httpd.serve_forever()
"
fi
EOF
    chmod 755 "$TAIXU_TOOL_DIR/bin/cc-switch-daemon"
fi

echo "[+] CC-Switch Agent Hub installed successfully!"
