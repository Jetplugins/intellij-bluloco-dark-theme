#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
screenshot_dir="${1:-${project_dir}/marketplace/screenshots}"
output_file="${2:-${project_dir}/marketplace/media/bluloco-demo.mp4}"
ffmpeg_binary="${FFMPEG:-ffmpeg}"

if ! command -v "${ffmpeg_binary}" >/dev/null 2>&1; then
  echo "ffmpeg is required to create the Marketplace demo video." >&2
  exit 1
fi

shopt -s nullglob
slides=()
for scenario in editor settings completion tool-windows diff; do
  matches=("${screenshot_dir}"/??-"${scenario}"-*.png)
  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "Expected one generated ${scenario} comparison in ${screenshot_dir}, found ${#matches[@]}." >&2
    exit 1
  fi
  slides+=("${matches[0]}")
done

mkdir -p "$(dirname "${output_file}")"

inputs=()
for slide in "${slides[@]}"; do
  inputs+=(-loop 1 -t 3 -i "${slide}")
done

filter=""
for index in "${!slides[@]}"; do
  filter+="[${index}:v]fps=30,format=yuv420p,setpts=PTS-STARTPTS[v${index}];"
done
filter+="[v0][v1]xfade=transition=fade:duration=0.5:offset=2.5[x1];"
filter+="[x1][v2]xfade=transition=fade:duration=0.5:offset=5.0[x2];"
filter+="[x2][v3]xfade=transition=fade:duration=0.5:offset=7.5[x3];"
filter+="[x3][v4]xfade=transition=fade:duration=0.5:offset=10.0[video]"

"${ffmpeg_binary}" -hide_banner -loglevel error -y \
  "${inputs[@]}" \
  -filter_complex "${filter}" \
  -map "[video]" \
  -t 13 \
  -an \
  -c:v libx264 \
  -preset medium \
  -crf 18 \
  -pix_fmt yuv420p \
  -movflags +faststart \
  "${output_file}"

echo "13-second Marketplace demo video created at ${output_file}"
