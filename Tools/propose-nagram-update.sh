#!/usr/bin/env bash
set -euo pipefail
test "$GITHUB_REPOSITORY" = yuhuan17520-glitch/Nooagram
test "$(node -p 'require("./nooagram-version.json").upstream_repository')" = NextAlone/Nagram
case "$(git remote get-url --push origin)" in
  https://github.com/yuhuan17520-glitch/Nooagram.git|https://github.com/yuhuan17520-glitch/Nooagram|git@github.com:yuhuan17520-glitch/Nooagram.git) ;;
  *) echo "Refusing to push: origin is not the Nooagram repository" >&2; exit 1 ;;
esac
test -z "$(git status --porcelain)"
if git remote get-url nagram-sync > /dev/null 2>&1; then
  test "$(git remote get-url nagram-sync)" = https://github.com/NextAlone/Nagram.git
else
  git remote add nagram-sync https://github.com/NextAlone/Nagram.git
fi
git fetch --no-tags nagram-sync main
target=$(git rev-parse FETCH_HEAD)
pin=$(node -p 'require("./nooagram-version.json").upstream_commit')
git merge-base --is-ancestor "$pin" HEAD
if git merge-base --is-ancestor "$target" HEAD; then exit 0; fi
# Refuse rewritten upstream history.
git merge-base --is-ancestor "$pin" "$target"
branch="sync/nagram-${target:0:12}"
ensure_pr() {
  # A retry after push succeeded but PR creation failed should finish the proposal.
  # Do not recreate proposals which were deliberately closed or already merged.
  local existing_prs
  existing_prs=$(gh pr list --state all --head "$branch" --json number --jq 'length')
  if test "$existing_prs" != 0; then return; fi
  gh pr create --base ayu --head "$branch" --title "Update Nagram stable to ${target:0:12}" --body "Merged stable main in a review branch. Pin: $target. No force push, automatic merge, or release. Review AyuMoments, workflows, upgrade compatibility and version floor before promotion. Run migration checks explicitly if this token-created PR does not start CI."
}
existing_branch=$(git ls-remote --heads origin "refs/heads/$branch")
if test -n "$existing_branch"; then
  ensure_pr
  exit 0
fi
git config user.name 'Nooagram Bot'
git config user.email 'actions@github.com'
git switch -c "$branch"
if ! git merge --no-ff --no-edit "$target"; then
  conflicts=$(git diff --name-only --diff-filter=U)
  git merge --abort
  title="Nagram update conflict ${target:0:12}"
  if ! gh issue list --state open --json title --jq '.[].title' | grep -Fxq "$title"; then
    conflict_file=$(mktemp)
    trap 'rm -f -- "$conflict_file"' EXIT
    printf 'Upstream stable main: %s\n\nResolve manually; no release was triggered.\n\nConflicted files:\n%s\n' "$target" "$conflicts" > "$conflict_file"
    gh issue create --title "$title" --body-file "$conflict_file"
  fi
  exit 1
fi
upstream_gradle=$(git show "$target:TMessagesProj/build.gradle")
TARGET_SHA="$target" UPSTREAM_GRADLE="$upstream_gradle" node <<'NODE'
const fs = require('fs');
const file = 'nooagram-version.json';
const metadata = JSON.parse(fs.readFileSync(file, 'utf8'));
const upstream = process.env.UPSTREAM_GRADLE;
const match = upstream.match(/def verCode = (\d+)/);
if (!match) throw new Error('Upstream version format changed; manual review required');
metadata.upstream_commit = process.env.TARGET_SHA;
metadata.upstream_build = Number(match[1]);
metadata.version_code_floor += 1000;
if (!Number.isSafeInteger(metadata.version_code_floor) || metadata.version_code_floor > 2100000000 ||
    metadata.version_code_floor <= metadata.previous_release_version_code) throw new Error('Android version floor is exhausted or invalid');
metadata.migration_ready = false;
fs.writeFileSync(file, JSON.stringify(metadata, null, 2) + '\n');
NODE
git add -- nooagram-version.json
git commit -m 'chore: pin proposed Nagram stable update'
git push origin "HEAD:refs/heads/$branch"
ensure_pr
