#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${SCREENSHOT_OUTPUT_DIR:-${project_dir}/marketplace/screenshots}"
ide_log="${project_dir}/build/ui-test/ide.log"
xvfb_pid=""

mkdir -p "$(dirname "${ide_log}")" "${output_dir}"
cd "${project_dir}"

# Remove only previously generated PNGs so deleted or renamed themes cannot leave stale assets.
find "${output_dir}" -type f -name '*.png' -delete

if [[ "$(uname -s)" == "Linux" && -z "${DISPLAY:-}" ]]; then
  export DISPLAY=:99.0
  Xvfb -ac :99 -screen 0 1920x1080x24 >"${project_dir}/build/ui-test/xvfb.log" 2>&1 &
  xvfb_pid=$!
fi

./gradlew --no-daemon runIdeForUiTests >"${ide_log}" 2>&1 &
ide_pid=$!

cleanup() {
  if kill -0 "${ide_pid}" 2>/dev/null; then
    kill "${ide_pid}" 2>/dev/null || true
  fi
  if [[ -n "${xvfb_pid}" ]] && kill -0 "${xvfb_pid}" 2>/dev/null; then
    kill "${xvfb_pid}" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

for attempt in $(seq 1 90); do
  if curl --silent --fail http://127.0.0.1:8082 >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "${ide_pid}" 2>/dev/null; then
    tail -n 120 "${ide_log}"
    exit 1
  fi
  if [[ "${attempt}" == "90" ]]; then
    tail -n 120 "${ide_log}"
    echo "Robot server did not become ready within three minutes." >&2
    exit 1
  fi
  sleep 2
done

./gradlew --no-daemon uiScreenshotTest \
  -PscreenshotDir="${output_dir}" \
  -PcloseIde=true

wait "${ide_pid}" || true
trap - EXIT INT TERM

echo "Marketplace screenshots for every registered theme created in ${output_dir}"
