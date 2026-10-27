#!/usr/bin/env bash
# Fails if anything shaped like a Discord token is committed.
# Test fixtures build fake tokens at runtime, so no exceptions are needed.
set -euo pipefail
pattern='(mfa\.[A-Za-z0-9_-]{60,120}|[A-Za-z0-9_-]{20,30}\.[A-Za-z0-9_-]{5,8}\.[A-Za-z0-9_-]{25,110})'
hits=$(git ls-files -z \
  | grep -zvE '\.(png|jpg|jpeg|webp|jar|ttf|otf)$' \
  | xargs -0 grep -nIoE "$pattern" 2>/dev/null || true)
if [ -n "$hits" ]; then
  echo "Possible Discord token committed:" >&2
  echo "$hits" | sed -E 's/(:[^:]{6})[^:]*$/\1…[redacted]/' >&2
  exit 1
fi
echo "No tokens found."
