#!/usr/bin/env bash
# Source this file to get adb, java, and gradle on your shell:
#   source scripts/env.sh
#
# Or make it permanent (one-time, idempotent):
#   echo '[ -f ~/projects/CCWEAROS/scripts/env.sh ] && source ~/projects/CCWEAROS/scripts/env.sh' >> ~/.zshrc

# --- Java -------------------------------------------------------------------
# Prefer Homebrew openjdk@21 (installed without sudo), fall back to Android
# Studio's bundled JBR.
if [ -d "/opt/homebrew/opt/openjdk@21" ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
  export PATH="$JAVA_HOME/bin:$PATH"
elif [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# --- Android SDK ------------------------------------------------------------
# Default Studio install path. Populated after Studio's first-run wizard
# accepts the SDK download.
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Platform-tools is also installed standalone via Homebrew cask at
# /opt/homebrew/bin/adb, so adb works regardless of $ANDROID_HOME.
if [ -d "$ANDROID_HOME/platform-tools" ]; then
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
fi
if [ -d "$ANDROID_HOME/emulator" ]; then
  export PATH="$ANDROID_HOME/emulator:$PATH"
fi
if [ -d "$ANDROID_HOME/cmdline-tools/latest/bin" ]; then
  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
fi

# --- Diagnostics ------------------------------------------------------------
ccwearos_doctor() {
  echo "JAVA_HOME=$JAVA_HOME"
  command -v java >/dev/null && java --version 2>&1 | head -1 || echo "java: NOT FOUND"
  command -v adb >/dev/null && adb --version 2>&1 | head -1 || echo "adb: NOT FOUND"
  if [ -d "$ANDROID_HOME" ]; then
    echo "ANDROID_HOME=$ANDROID_HOME (SDK present)"
  else
    echo "ANDROID_HOME=$ANDROID_HOME (NOT present — run Studio first-run wizard to install SDK)"
  fi
}
