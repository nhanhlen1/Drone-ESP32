#!/usr/bin/env bash
# Source in each task. Keep all SDKs and caches outside repository.
export DRONE_TOOLING_ROOT=/workspace/tooling
export JAVA_HOME="$DRONE_TOOLING_ROOT/jdk-21.0.9+10"
export ANDROID_HOME="$DRONE_TOOLING_ROOT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$DRONE_TOOLING_ROOT/android-user"
export GRADLE_USER_HOME="$DRONE_TOOLING_ROOT/gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/19.0/bin:$PATH"
export CI=true
# Java does not consume HTTPS_PROXY. Use its supported proxy/trust settings.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
  drone_java_proxy="$(python3 - <<'PY'
import os,urllib.parse
p=urllib.parse.urlsplit(os.environ['HTTPS_PROXY'])
if p.username or p.password: raise SystemExit('Proxy authentication must be supplied by the platform, not copied into scripts.')
if p.hostname: print(f'-Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={p.port or 80} -Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={p.port or 80}')
PY
)" || return 1
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $drone_java_proxy"
  unset drone_java_proxy
fi
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
