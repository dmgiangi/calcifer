#!/usr/bin/env bash
set -euo pipefail
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/rage-quit-java.sh"
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
rage_quit_prepare_java "$scratch"
for script in "$rage_quit_repo"/scripts/rage-quit*.sh; do bash -n "$script"; done
for version in 0.1.0 1.2.3 1.2.3-rc.1 1.2.3-0; do bash "$rage_quit_repo/scripts/rage-quit-version.sh" "$version"; done
for version in '' 01.2.3 1.02.3 1.2.03 v1.2.3 1.2 1.2.3-01 1.2.3-a..b 1.2.3+meta '1.2.3;exit'; do
  if bash "$rage_quit_repo/scripts/rage-quit-version.sh" "$version" >/dev/null 2>&1; then
    printf 'Unsafe version was accepted.\n' >&2; exit 1
  fi
done
kubectl kustomize "$rage_quit_repo/clusters/apps/rage-quit/overlays/cloud" > "$scratch/app.yaml"
kubectl kustomize "$rage_quit_repo/clusters/calcifer-cloud" > "$scratch/cloud.yaml"
kubectl kustomize "$rage_quit_repo/clusters/calcifer-home" > "$scratch/home.yaml"
# Auth renders can contain encrypted Secret data. Keep them only in this private
# scratch directory for metadata-only checks; never decrypt or print their values.
for edge in cloud home; do
  kubectl kustomize "$rage_quit_repo/clusters/apps/authorization-server/overlays/$edge" > "$scratch/auth-$edge.yaml"
done
"$rage_quit_java" --class-path "$rage_quit_classpath" "$rage_quit_repo/scripts/rage-quit-check.java" \
  "$scratch/app.yaml" "$scratch/cloud.yaml" "$scratch/home.yaml" "$rage_quit_repo/.github/workflows/release-rage-quit.yaml" \
  "$rage_quit_repo/clusters/apps/rage-quit/overlays/cloud/secret-template.yaml" \
  "$rage_quit_repo/clusters/apps/rage-quit/overlays/cloud/kustomization.yaml" \
  "$scratch/auth-cloud.yaml" "$scratch/auth-home.yaml" \
  "$rage_quit_repo/authorization-server/src/main/resources/application-rage-quit.yaml"
if command -v actionlint >/dev/null; then actionlint "$rage_quit_repo/.github/workflows/release-rage-quit.yaml"; fi
if command -v shellcheck >/dev/null; then shellcheck -x -P "$rage_quit_repo/scripts" "$rage_quit_repo"/scripts/rage-quit*.sh; fi
