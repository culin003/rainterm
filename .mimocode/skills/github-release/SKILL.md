---
description: Complete GitHub release workflow - tag, push, create release, upload assets
---

# GitHub Release Workflow

Complete workflow for creating GitHub releases for the Raindrop project. This skill covers tag management, release creation, and asset upload.

## Prerequisites

1. Git credentials configured at `~/.git-credentials`
2. GitHub repository `culin003/rainterm` (or update in commands)
3. Release notes prepared (RELEASE_NOTES.md or inline)

## Step 1: Manage Tags

```bash
# List existing tags
git tag -l

# Delete old tag (if re-releasing)
git tag -d v0.1.0 && git push origin --delete v0.1.0

# Create new tag
git tag -a v0.1.0 -m "Release v0.1.0" && git push origin v0.1.0
```

## Step 2: Create GitHub Release

```bash
# Create release via GitHub API
RELEASE_NOTES=$(cat /home/cooper/raindrop/RELEASE_NOTES.md)
curl -s -X POST \
  -H "Authorization: token $(cat ~/.git-credentials | sed 's/https:\/\/[^:]*:\([^@]*\)@.*/\1/')" \
  -H "Accept: application/vnd.github.v3+json" \
  https://api.github.com/repos/culin003/rainterm/releases \
  -d "{\"tag_name\":\"v0.1.0\",\"name\":\"Release v0.1.0\",\"body\":\"$RELEASE_NOTES\"}"
```

## Step 3: Upload Release Assets

```bash
# Upload asset to release
curl -sL -H "Authorization: token $(cat ~/.git-credentials | sed 's/https:\/\/[^:]*:\([^@]*\)@.*/\1/')" \
  -H "Accept: application/vnd.github.v3+json" \
  "https://uploads.github.com/repos/culin003/rainterm/releases/RELEASE_ID/assets?name=filename.zip" \
  --data-binary @/path/to/asset
```

## Step 4: Verify Release

```bash
# Check release status
curl -sL -H "Authorization: token $(cat ~/.git-credentials | sed 's/https:\/\/[^:]*:\([^@]*\)@.*/\1/')" \
  "https://api.github.com/repos/culin003/rainterm/releases/latest" | python3 -c "
import json, sys
data = json.load(sys.stdin)
print(f'Tag: {data[\"tag_name\"]}')
print(f'Name: {data[\"name\"]}')
print(f'Assets: {len(data[\"assets\"])}')
"
```

## Token Management

```bash
# Extract token from git-credentials
cat ~/.git-credentials | sed 's/https:\/\/[^:]*:\([^@]*\)@.*/\1/'

# Token scopes required: repo, workflow
```

## Troubleshooting

### CI Workflow Fails
```bash
# Check workflow run status
curl -sL -H "Authorization: token TOKEN" \
  "https://api.github.com/repos/culin003/rainterm/actions/runs?per_page=1" | python3 -c "
import json, sys
data = json.load(sys.stdin)
run = data['workflow_runs'][0]
print(f'Status: {run[\"status\"]}')
print(f'Conclusion: {run[\"conclusion\"]}')
"

# Get job logs
curl -sL -H "Authorization: token TOKEN" \
  "https://api.github.com/repos/culin003/rainterm/actions/runs/RUN_ID/jobs" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for job in data['jobs']:
    print(f'{job[\"name\"]}: {job[\"conclusion\"]}')
"
```

### Upload Fails
- Check token has `workflow` scope for `.github/workflows/` files
- Verify release ID is correct
- Check file exists at upload path

## Notes

- macOS builds require version > 0.0.0 (jpackage constraint)
- CI must skip SSH integration tests (require real server)
- JavaFX aarch64 builds use bare `21` version (not 21.0.x)
