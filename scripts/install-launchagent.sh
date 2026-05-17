#!/usr/bin/env bash
# Install the CCWEAROS wrapper as a macOS LaunchAgent so it boots in daemon
# mode at every login and restarts itself on crash.
#
#   bash scripts/install-launchagent.sh         # install + start
#   bash scripts/install-launchagent.sh stop    # stop the running daemon
#   bash scripts/install-launchagent.sh uninstall

set -euo pipefail

LABEL="com.caamano.ccwearos"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
WRAPPER_DIR="$HOME/projects/CCWEAROS/wrapper"
LOG_DIR="$HOME/Library/Logs"
NODE_BIN="$(command -v node || echo /usr/local/bin/node)"
TSX_BIN="$WRAPPER_DIR/node_modules/.bin/tsx"

case "${1:-install}" in
  uninstall)
    echo "[launchagent] Unloading + removing $LABEL"
    launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
    rm -f "$PLIST"
    echo "[launchagent] Done."
    exit 0
    ;;
  stop)
    echo "[launchagent] Stopping $LABEL"
    launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
    echo "[launchagent] Stopped."
    exit 0
    ;;
esac

if [ ! -d "$WRAPPER_DIR" ]; then
  echo "ERROR: wrapper dir not found at $WRAPPER_DIR" >&2
  exit 1
fi
if [ ! -x "$TSX_BIN" ]; then
  echo "ERROR: tsx not found at $TSX_BIN — run 'npm install' in $WRAPPER_DIR first" >&2
  exit 1
fi

mkdir -p "$HOME/Library/LaunchAgents" "$LOG_DIR"

# Build the plist with absolute paths. LaunchAgents don't inherit the user's
# interactive PATH, so we set it explicitly.
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${NODE_BIN}</string>
        <string>${TSX_BIN}</string>
        <string>src/index.ts</string>
    </array>
    <key>WorkingDirectory</key>
    <string>${WRAPPER_DIR}</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>CCWEAROS_MODE</key>
        <string>daemon</string>
        <key>PATH</key>
        <string>${HOME}/.local/bin:$(dirname "$NODE_BIN"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${LOG_DIR}/ccwearos.log</string>
    <key>StandardErrorPath</key>
    <string>${LOG_DIR}/ccwearos.err.log</string>
    <key>ThrottleInterval</key>
    <integer>10</integer>
</dict>
</plist>
EOF

echo "[launchagent] Wrote $PLIST"
echo "[launchagent] Reloading (so changes take effect even if already installed)..."
launchctl bootout "gui/$(id -u)" "$PLIST" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl enable "gui/$(id -u)/${LABEL}"

echo ""
echo "[launchagent] Daemon installed + started."
echo "  Logs: $LOG_DIR/ccwearos.log"
echo "  Errs: $LOG_DIR/ccwearos.err.log"
echo "  Tail: tail -f $LOG_DIR/ccwearos.log"
echo ""
echo "Status:"
launchctl print "gui/$(id -u)/${LABEL}" 2>&1 | grep -E "^\s+(state|pid|last exit code)" | head -5 || true
