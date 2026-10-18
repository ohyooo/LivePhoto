#!/usr/bin/env bash
set -euo pipefail
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || { echo 'Web fixture tools require Linux x64.' >&2; exit 1; }
ffmpeg_cache="$RUNNER_TOOL_CACHE/livephoto-web-ffmpeg/n9.0"
if [[ ! -x "$ffmpeg_cache/ffmpeg" || ! -x "$ffmpeg_cache/ffprobe" || ! -f "$ffmpeg_cache/binaries.sha256" ]]; then
  ffmpeg_download="$(mktemp -d "$RUNNER_TEMP/livephoto-web-ffmpeg.XXXXXX")"
  ffmpeg_asset=ffmpeg-n9.0-latest-linux64-gpl-9.0.tar.xz
  ffmpeg_release=https://github.com/BtbN/FFmpeg-Builds/releases/download/latest
  curl --fail --location --retry 3 --max-time 600 "$ffmpeg_release/$ffmpeg_asset" -o "$ffmpeg_download/$ffmpeg_asset"
  curl --fail --location --retry 3 --max-time 60 "$ffmpeg_release/checksums.sha256" -o "$ffmpeg_download/checksums.sha256"
  awk -v asset="$ffmpeg_asset" '$2 == asset || $2 == "*" asset || $2 == "./" asset { print }' "$ffmpeg_download/checksums.sha256" > "$ffmpeg_download/selected.sha256"
  [[ "$(wc -l < "$ffmpeg_download/selected.sha256")" -eq 1 ]] || { echo 'Missing unique published FFmpeg checksum.' >&2; exit 1; }
  (cd "$ffmpeg_download"; sha256sum --check selected.sha256)
  mkdir -p "$ffmpeg_cache"
  tar -xJf "$ffmpeg_download/$ffmpeg_asset" -C "$ffmpeg_cache" --strip-components=2 \
    "${ffmpeg_asset%.tar.xz}/bin/ffmpeg" "${ffmpeg_asset%.tar.xz}/bin/ffprobe"
  chmod +x "$ffmpeg_cache/ffmpeg" "$ffmpeg_cache/ffprobe"
  (cd "$ffmpeg_cache"; sha256sum ffmpeg ffprobe > binaries.sha256)
fi
(cd "$ffmpeg_cache"; sha256sum --check binaries.sha256)
"$ffmpeg_cache/ffmpeg" -version
ffmpeg_version="$("$ffmpeg_cache/ffmpeg" -version)"
grep -Eq '^ffmpeg version (n)?9\.0([.-]|[[:space:]])' <<< "$ffmpeg_version"
"$ffmpeg_cache/ffprobe" -version
ffmpeg_encoders="$("$ffmpeg_cache/ffmpeg" -hide_banner -encoders 2>&1)"
for encoder in libx264 libx265 aac; do
  grep -qw "$encoder" <<< "$ffmpeg_encoders"
done
echo "LIVEPHOTO_FFMPEG=$ffmpeg_cache/ffmpeg" >> "$GITHUB_ENV"
echo 'Cached stable FFmpeg ready for synthetic-only Web validation.'
