# Nooagram

Nooagram is a fork of [nextalone/nagram](https://github.com/nextalone/nagram).

## Automation

- `ayu` is the release branch and the repository default branch.
- `Sync Nagram upstream dev` runs every 15 minutes and tracks the official `dev` branch, where Nagram publishes frequent updates.
- If upstream changes merge cleanly, the workflow pushes `ayu` and starts `Release Build`.
- If an upstream merge conflicts, it opens a GitHub issue with the conflicted files and does not publish a broken APK.
- `Release Build` publishes arm32 and arm64 APKs, `SHA256SUMS.txt`, and `update.json` to GitHub Releases.

## In-app update source

The app checks:

```text
https://github.com/yuhuan17520-glitch/Nooagram/releases/latest/download/update.json
```

The `download_url` field points to the arm64 APK from the same GitHub Release.

## Signing

Nooagram signs release APKs with a generated local keystore. The CI keystore and
passwords are stored as GitHub Actions secrets:

- `NOOAGRAM_KEYSTORE_BASE64`
- `LOCAL_PROPERTIES`

A local backup is kept outside the repository in:

```text
C:/Users/YuHuan/Documents/Codex/2026-09-07/https-github-com-risin42-nagramx-https/work/nooagram-secrets
```
