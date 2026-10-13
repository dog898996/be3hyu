#!/usr/bin/env bash
# Pinned Linux x86_64 build tools. Downloads stay outside the app's source tree.
# Usage: build-android.sh [--prepare] [mobile-project-directory] [Gradle tasks...]
set -euo pipefail

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  echo 'This bootstrap supports Linux x86_64. Use Android Studio elsewhere.' >&2
  exit 1
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PREPARE_ONLY=false
if [[ "${1:-}" == --prepare ]]; then
  PREPARE_ONLY=true
  shift
fi
PROJECT_DIR="${1:-$(dirname -- "$SCRIPT_DIR")}"
if [[ $# -gt 0 ]]; then shift; fi
TOOLS_DIR="${HOYOUNG_ANDROID_TOOLS_DIR:-/workspace/.tools/android-build}"
DOWNLOADS_DIR="$TOOLS_DIR/downloads"
SDK_DIR="$TOOLS_DIR/sdk"
JDK_DIR="$TOOLS_DIR/jdk-17.0.16+8"
GRADLE_DIR="$TOOLS_DIR/gradle-8.11.1"
GRADLE_CACHE_DIR="${HOYOUNG_GRADLE_CACHE_DIR:-$TOOLS_DIR/gradle-cache}"
CMDLINE_DIR="$SDK_DIR/cmdline-tools/15859902"
mkdir -p "$DOWNLOADS_DIR" "$SDK_DIR/cmdline-tools" "$GRADLE_CACHE_DIR"

# SHA256 values published by Gradle, Adoptium's version API, and Google's
# Android Studio command-line tools download table. Verify before extracting.
fetch_verified() {
  local artifact_name="$1" artifact_url="$2" expected_sha256="$3"
  local artifact_path="$DOWNLOADS_DIR/$artifact_name"
  if [[ ! -f "$artifact_path" ]]; then
    curl --fail --location --retry 2 --silent --show-error \
      "$artifact_url" --output "$artifact_path.part"
    mv -- "$artifact_path.part" "$artifact_path"
  fi
  python3 - "$artifact_path" "$expected_sha256" <<'PY'
import hashlib, pathlib, sys
artifact = pathlib.Path(sys.argv[1])
with artifact.open('rb') as stream:
    actual = hashlib.file_digest(stream, 'sha256').hexdigest()
if actual != sys.argv[2]:
    raise SystemExit(f'SHA256 mismatch: {artifact.name}; remove it and retry.')
print(f'Verified {artifact.name}')
PY
}

if [[ ! -x "$JDK_DIR/bin/javac" ]]; then
  fetch_verified 'OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz' \
    'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz' \
    '166774efcf0f722f2ee18eba0039de2d685b350ee14d7b69e6f83437dafd2af1'
  tar -xzf "$DOWNLOADS_DIR/OpenJDK17U-jdk_x64_linux_hotspot_17.0.16_8.tar.gz" -C "$TOOLS_DIR"
fi

if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
  fetch_verified 'gradle-8.11.1-bin.zip' \
    'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' \
    'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
  unzip -q "$DOWNLOADS_DIR/gradle-8.11.1-bin.zip" -d "$TOOLS_DIR"
fi

if [[ ! -x "$CMDLINE_DIR/bin/sdkmanager" ]]; then
  fetch_verified 'commandlinetools-linux-15859902_latest.zip' \
    'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' \
    '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583'
  CMDLINE_TEMP_DIR="$(mktemp -d "$TOOLS_DIR/cmdline-tools.XXXXXX")"
  unzip -q "$DOWNLOADS_DIR/commandlinetools-linux-15859902_latest.zip" -d "$CMDLINE_TEMP_DIR"
  mv -- "$CMDLINE_TEMP_DIR/cmdline-tools" "$CMDLINE_DIR"
  rmdir -- "$CMDLINE_TEMP_DIR"
fi

# Java tools do not automatically honor HTTP(S)_PROXY. Preserve the session
# proxy and use the system Java CA store, which contains its trusted CA.
mapfile -t PROXY_ARGS < <(python3 - <<'PY'
import os, urllib.parse
for scheme in ('http', 'https'):
    proxy = os.environ.get(scheme.upper() + '_PROXY') or os.environ.get(scheme + '_proxy')
    if proxy:
        parsed = urllib.parse.urlparse(proxy)
        if parsed.hostname:
            print(f'-D{scheme}.proxyHost={parsed.hostname}')
            print(f'-D{scheme}.proxyPort={parsed.port or 80}')
PY
)
TRUST_ARGS=()
if [[ -f /etc/ssl/certs/java/cacerts ]]; then
  TRUST_ARGS=(-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts)
fi
SDK_PROXY_ARGS=()
mapfile -t SDK_PROXY_ARGS < <(python3 - <<'PY'
import os, urllib.parse
proxy = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if proxy:
    parsed = urllib.parse.urlparse(proxy)
    if parsed.hostname:
        print('--proxy=http')
        print('--proxy_host=' + parsed.hostname)
        print('--proxy_port=' + str(parsed.port or 80))
PY
)
SDK_JAVA_OPTIONS="${JAVA_OPTS:-} ${TRUST_ARGS[*]}"

if [[ ! -f "$SDK_DIR/platforms/android-35/android.jar" || ! -x "$SDK_DIR/build-tools/35.0.0/aapt2" ]]; then
  # User requested building the app; SDK terms are accepted for this build only.
  python3 - "$JDK_DIR" "$SDK_DIR" "$CMDLINE_DIR/bin/sdkmanager" "$SDK_JAVA_OPTIONS" "$TOOLS_DIR" "${SDK_PROXY_ARGS[@]}" <<'PY'
import os, pathlib, subprocess, sys
sdk_env = dict(os.environ, JAVA_HOME=sys.argv[1], ANDROID_HOME=sys.argv[2], JAVA_OPTS=sys.argv[4])
command = [sys.argv[3], '--sdk_root=' + sys.argv[2], *sys.argv[6:]]
license_log = pathlib.Path(sys.argv[5]) / 'sdk-license-acceptance.log'
with license_log.open('w') as stream:
    subprocess.run([*command, '--licenses'], input='y\n' * 100, text=True, env=sdk_env, check=True, stdout=stream, stderr=subprocess.STDOUT)
print('SDK build licenses accepted; transcript:', license_log)
subprocess.run([*command, '--install', 'platforms;android-35', 'build-tools;35.0.0'], env=sdk_env, check=True)
PY
fi

echo "Android SDK: $SDK_DIR"
echo "Java compiler: $JDK_DIR/bin/javac"
echo "Gradle: $GRADLE_DIR/bin/gradle"
if $PREPARE_ONLY; then exit 0; fi
if [[ ! -f "$PROJECT_DIR/settings.gradle" && ! -f "$PROJECT_DIR/settings.gradle.kts" ]]; then
  echo "Missing Gradle settings in $PROJECT_DIR" >&2
  exit 1
fi
if [[ $# -eq 0 ]]; then set -- assembleDebug; fi
# Android unit tests run in separate JVMs. Forward only non-secret network/CA
# properties so Robolectric's SDK artifact downloader uses the same proxy.
NETWORK_INIT_SCRIPT="$TOOLS_DIR/cloud-java-network.init.gradle"
cat > "$NETWORK_INIT_SCRIPT" <<'GRADLE'
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        ['http.proxyHost', 'http.proxyPort', 'https.proxyHost', 'https.proxyPort',
         'javax.net.ssl.trustStore'].each { propertyName ->
            def propertyValue = System.getProperty(propertyName)
            if (propertyValue != null) systemProperty propertyName, propertyValue
        }
        systemProperty 'robolectric.dependency.repo.url', 'https://repo.maven.apache.org/maven2'
    }
}
GRADLE
exec env JAVA_HOME="$JDK_DIR" ANDROID_HOME="$SDK_DIR" ANDROID_SDK_ROOT="$SDK_DIR" \
  GRADLE_USER_HOME="$GRADLE_CACHE_DIR" "$GRADLE_DIR/bin/gradle" \
  --no-daemon --max-workers=2 --console=plain -I "$NETWORK_INIT_SCRIPT" -p "$PROJECT_DIR" \
  "${TRUST_ARGS[@]}" "${PROXY_ARGS[@]}" "$@"
