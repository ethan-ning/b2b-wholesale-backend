#!/usr/bin/env bash
# Builds the portal, drops its output beside this file, and builds the one image.
#
# The portal lives in its own repository; point FRONTEND_DIR at it if it is not the
# sibling directory. Nothing here is Cloud-specific — the same image runs locally.
set -euo pipefail

FRONTEND_DIR="${FRONTEND_DIR:-../b2b-wholesale-frontend}"
IMAGE="${IMAGE:-b2b-wholesale:local}"

if [ ! -d "$FRONTEND_DIR" ]; then
  echo "No portal at $FRONTEND_DIR. Set FRONTEND_DIR, or build the API alone with docker build." >&2
  exit 1
fi

echo "==> Building the portal"
( cd "$FRONTEND_DIR" && npm ci && npm run build )

echo "==> Collecting it"
rm -rf frontend-dist
cp -R "$FRONTEND_DIR/dist" frontend-dist

echo "==> Building $IMAGE"
docker build -t "$IMAGE" .

echo "==> $IMAGE"
