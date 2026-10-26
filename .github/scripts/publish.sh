#!/usr/bin/env bash
# Puts build outputs on a DRAFT GitHub release (drafts are only visible to people who
# can push to the repo, so a YouTube API key inside the app isn't made public).
#   publish.sh TAG "Notes" FILE...
set -uo pipefail
tag="$1"; notes="$2"; shift 2

files=()
for f in "$@"; do [ -f "$f" ] && files+=("$f"); done
[ ${#files[@]} -eq 0 ] && { echo "Nothing to publish"; exit 0; }

if ! gh release view "$tag" >/dev/null 2>&1; then
  gh release create "$tag" --draft --title "$tag" --notes "$notes" || true
fi
gh release edit "$tag" --notes "$notes (commit ${GITHUB_SHA:0:7}, $(date -u '+%Y-%m-%d %H:%M UTC'))" >/dev/null || true
gh release upload "$tag" "${files[@]}" --clobber
