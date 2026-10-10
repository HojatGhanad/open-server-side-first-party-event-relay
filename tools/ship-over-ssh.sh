#!/usr/bin/env bash
# Copy Docker images to a server that cannot reach container registries.
#
#   ./tools/ship-over-ssh.sh user@host [image ...]
#
# Defaults to the relay and sGTM images from docker-compose.yml. Pull (or build) the
# images on a machine with registry access first. The image ID is verified on both ends.
#
# On Windows, run this from WSL or Git Bash. PowerShell pipelines corrupt binary data.
set -euo pipefail

TARGET="${1:?usage: $0 user@host [image ...]}"
shift || true

IMAGES=("$@")
if [ ${#IMAGES[@]} -eq 0 ]; then
  IMAGES=(
    "${FP_RELAY_IMAGE:-ghcr.io/hojatghanad/fp-relay:latest}"
    "${FP_SGTM_IMAGE:-gcr.io/cloud-tagging-10302018/gtm-cloud-image:stable}"
  )
fi

for img in "${IMAGES[@]}"; do
  echo "==> ${img}"
  docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img"
  local_id="$(docker image inspect --format '{{.Id}}' "$img")"
  docker save "$img" | gzip | ssh "$TARGET" 'gunzip | docker load'
  remote_id="$(ssh "$TARGET" "docker image inspect --format '{{.Id}}' '$img'")"
  if [ "$local_id" != "$remote_id" ]; then
    echo "!! image ID mismatch for ${img}: local ${local_id}, remote ${remote_id}" >&2
    exit 1
  fi
  echo "    ok ${local_id:7:12}"
done
