#!/usr/bin/env bash
set -euo pipefail

# Usage: bash scripts/termux-push.sh "Commit message" [paths to stage...]
# With no paths, commit files already staged with git add. If the previous
# push failed after a successful commit, rerun to push the existing commit.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if (( $# < 1 )) || [[ -z "${1//[[:space:]]/}" ]]; then
    echo 'Usage: bash scripts/termux-push.sh "Commit message" [paths to stage...]' >&2
    exit 2
fi
message=$1
shift

for command_name in git gh; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "Missing $command_name. In Termux run: pkg install git gh" >&2
        exit 1
    fi
done

repo_root=$(git rev-parse --show-toplevel 2>/dev/null || true)
if [[ -z "$repo_root" || ! "$repo_root" -ef . ]]; then
    echo 'Run this script from a clone of Ncorror/NekoFlash-Pro, not from a ZIP.' >&2
    echo 'First run: gh repo clone Ncorror/NekoFlash-Pro' >&2
    exit 1
fi

origin=$(git remote get-url origin 2>/dev/null || true)
case "$origin" in
    https://github.com/Ncorror/NekoFlash-Pro|\
    https://github.com/Ncorror/NekoFlash-Pro.git|\
    git@github.com:Ncorror/NekoFlash-Pro.git|\
    ssh://git@github.com/Ncorror/NekoFlash-Pro.git) ;;
    *) echo 'Refusing to push: origin is not Ncorror/NekoFlash-Pro.' >&2; exit 1 ;;
esac

if ! gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI is not signed in. Run: gh auth login" >&2
    exit 1
fi
if [[ "$(gh api user --jq .login)" != 'Ncorror' ]]; then
    echo 'GitHub CLI is signed in to another account, not Ncorror.' >&2
    exit 1
fi

branch=$(git symbolic-ref --quiet --short HEAD || true)
if [[ -z "$branch" ]]; then
    echo 'Cannot push a detached HEAD. Switch to a branch first.' >&2
    exit 1
fi

# Read the remote first so a stale clone does not accidentally push over
# changes made on GitHub or another device. Never force-push.
if ! git fetch --quiet origin; then
    echo 'Could not fetch origin. Check the network and run: gh auth setup-git' >&2
    exit 1
fi
if git show-ref --verify --quiet "refs/remotes/origin/$branch"; then
    if ! git merge-base --is-ancestor "origin/$branch" HEAD; then
        echo "origin/$branch has commits missing locally. Update and resolve any conflicts before pushing." >&2
        echo "After saving local changes: git pull --rebase origin $branch" >&2
        exit 1
    fi
fi

if (( $# > 0 )); then
    git add -A -- "$@"
fi

has_staged=false
if ! git diff --cached --quiet; then
    has_staged=true
    if ! git config user.name >/dev/null || ! git config user.email >/dev/null; then
        echo 'Set git user.name and git user.email before committing.' >&2
        exit 1
    fi
    git diff --cached --check
fi

has_unpushed=false
if git show-ref --verify --quiet "refs/remotes/origin/$branch"; then
    if [[ "$(git rev-list --count "origin/$branch..HEAD")" != 0 ]]; then
        has_unpushed=true
    fi
else
    has_unpushed=true
fi

if [[ "$has_staged" == false && "$has_unpushed" == false ]]; then
    echo 'Nothing staged or committed to push. Pass file paths or stage files with git add.'
    git status --short
    exit 0
fi

echo "Destination: Ncorror/NekoFlash-Pro, branch: $branch"
if [[ "$has_staged" == true ]]; then
    echo 'Files to commit:'
    git diff --cached --name-status
fi
if [[ "$has_unpushed" == true ]]; then
    echo 'Existing local commits to push:'
    if git show-ref --verify --quiet "refs/remotes/origin/$branch"; then
        git log --oneline "origin/$branch..HEAD"
    else
        git log -1 --oneline
    fi
fi

if [[ ! -t 0 ]]; then
    echo 'Interactive confirmation is required in Termux.' >&2
    exit 1
fi
read -r -p 'Commit (if needed) and push these changes? [y/N] ' answer
if [[ "$answer" != y && "$answer" != Y ]]; then
    echo 'Cancelled. Staged files were left as they were.'
    exit 0
fi

if [[ "$has_staged" == true ]]; then
    git commit -m "$message"
fi
git push --set-upstream origin "$branch"
echo 'Push complete. Check the Actions tab for the build.'
