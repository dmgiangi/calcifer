#!/usr/bin/env bash
# Called only by the publish job after all verify gates succeed.
set -euo pipefail
umask 077
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo"
[[ ${GITHUB_ACTIONS:-} == true && ${GITHUB_REF:-} == refs/heads/master ]] || { printf 'Publishing is restricted to the manual master workflow.\n' >&2; exit 1; }
bash scripts/rage-quit-version.sh "${VERSION:-}"
[[ ${IMAGE:-} == ghcr.io/dmgiangi/calcifer-rage-quit ]]
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
# Fail closed for registry outages/auth errors, not just a nonzero inspect exit.
if docker buildx imagetools inspect "$IMAGE:$VERSION" > "$scratch/manifest" 2> "$scratch/registry-error"; then
  printf 'Version already published; choose a new version.\n' >&2; exit 1
fi
if grep -Eqi '(unauthorized|denied|forbidden|timeout|connection refused)' "$scratch/registry-error"; then
  printf 'Registry immutability check failed.\n' >&2; exit 1
fi
grep -Eqi '(manifest unknown|not found)' "$scratch/registry-error" || { printf 'Registry immutability check failed.\n' >&2; exit 1; }
existing_tag=$(git ls-remote --tags origin "refs/tags/rage-quit-v$VERSION")
[[ -z $existing_tag ]]
docker load --input release-image/image.tar >/dev/null
docker tag calcifer-rage-quit:checked "$IMAGE:$VERSION"
docker push "$IMAGE:$VERSION"
digest=$(docker buildx imagetools inspect "$IMAGE:$VERSION" --format '{{.Manifest.Digest}}')
[[ $digest =~ ^sha256:[0-9a-f]{64}$ ]]
file=clusters/apps/rage-quit/overlays/cloud/image-patch.yaml
[[ $(grep -c '^[[:space:]]*image:' "$file") == 1 ]]
sed -i -E "s|^([[:space:]]*)image:.*|\1image: ${IMAGE}@${digest}|" "$file"
kubectl kustomize clusters/apps/rage-quit/overlays/cloud >/dev/null
[[ $(git diff --name-only) == "$file" ]]
git config user.name 'github-actions[bot]'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
git add -- "$file"
git commit -m "release(rage-quit): promote Cloud image $VERSION"
git tag "rage-quit-v$VERSION"
# Non-fast-forward/atomic rejection leaves publication unpromoted for review;
# never rebase or overwrite another Cloud/auth change automatically.
git push --atomic origin HEAD:master "refs/tags/rage-quit-v$VERSION"
