#!/usr/bin/env bash
# Source this file. All tool caches are external to the source checkout.
export DRONE_TOOLING_ROOT="${DRONE_TOOLING_ROOT:-/workspace/tooling}"
export JAVA_HOME="$DRONE_TOOLING_ROOT/jdk-21.0.9+10"
export PATH="$JAVA_HOME/bin:$DRONE_TOOLING_ROOT/flutter/bin:$PATH"
export PUB_CACHE="$DRONE_TOOLING_ROOT/pub-cache"
export XDG_CONFIG_HOME="$DRONE_TOOLING_ROOT/config"
export ANALYZER_STATE_LOCATION_OVERRIDE="$DRONE_TOOLING_ROOT/dart-server"
export FLUTTER_SUPPRESS_ANALYTICS=true
export CI=true
export ANDROID_HOME="$DRONE_TOOLING_ROOT/android-sdk"
export ANDROID_USER_HOME="$DRONE_TOOLING_ROOT/android-user"
export GRADLE_USER_HOME="$DRONE_TOOLING_ROOT/gradle"
# Java does not consume HTTPS_PROXY. Configure its supported proxy properties
# without copying credentials or changing certificate validation.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
  drone_proxy_options="$(python3 - <<'PY'
import os,urllib.parse
u=urllib.parse.urlsplit(os.environ['HTTPS_PROXY'])
if u.username or u.password:
    raise SystemExit('Proxy credentials require platform-supported Java authentication; refusing to copy them.')
if u.hostname:
    print(f'-Dhttps.proxyHost={u.hostname} -Dhttps.proxyPort={u.port or 80} -Dhttp.proxyHost={u.hostname} -Dhttp.proxyPort={u.port or 80}')
PY
)" || return 1
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $drone_proxy_options"
  unset drone_proxy_options
fi

# Reuse the platform's trusted CA bundle with the downloaded JDK. TLS remains verified.
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
