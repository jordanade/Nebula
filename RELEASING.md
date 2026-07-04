# Releasing Nebula

End-to-end procedure for cutting a versioned release and getting it onto F-Droid.
**Follow it top to bottom, in order, without skipping.** Every warning box below
is a real release that broke because someone skipped that exact step. If you do
all of Steps 0–5 in sequence, it works on the first CI run.

For one-off local builds (debug-signed, just to run on a device), see
[README.md → Build from source](README.md#build-from-source). This document is
only for **public releases** that must be reproducible and release-signed.

---

## Every way this has broken (read once)

| Release | What broke | The rule it violates |
|---------|-----------|----------------------|
| v4.7.0 | Uploaded a **debug-signed** APK | Step 2 — always `SIGNING_MODE=release` |
| v4.7.1 | Built from `main`, which was **ahead of the tag** → reproducibility mismatch | Step 2 — build from a clean checkout of the **tag** |
| v4.7.1 | Forgot to bump `CurrentVersion` → `checkupdates` CI job failed | Step 5 — add build entry **and** bump CurrentVersion |
| v4.6.8 | Triggered F-Droid CI **before** the APK was live on GitHub | Order — GitHub upload (Step 4) precedes fdroiddata (Step 5) |
| v4.8.0 | A CI workflow (`release.yml`) auto-created the release on tag push and attached a **debug-signed** APK, racing the verified upload | Step 3 — no CI may upload APK assets (workflow deleted 2026-07-04); always re-check the **live** shasum after uploading |

The two hard requirements F-Droid enforces, both or it rejects the build:

1. **Release key.** Signer cert SHA-256 must be
   `878ec6cee21525482bd880c97bde14e1be71a27d581502f7326457daf6693639`
   (`CN=Jordan Adema`) — the `AllowedAPKSigningKeys` value. The debug key
   (`CN=Android Debug`) is wrong and also breaks upgrades for existing users.
   `build.sh` self-checks this in release mode and aborts if it's wrong.
2. **Reproducible.** F-Droid rebuilds the **tag** unsigned, copies your signature
   onto it, and the result must be byte-identical and verify. This is why you
   build from the tag (not `main`), with `TZ=UTC`, and never strip
   `--alignment-preserved true` from `build.sh` or re-sign with another tool.

---

## Step 0 — Prerequisites (check once per machine)

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
ls "$ANDROID_HOME/build-tools/35.0.0/apksigner"        # build-tools;35.0.0 present
ls "$ANDROID_HOME/platforms/android-35/android.jar"    # platforms;android-35 present
python3 -c "import apksigcopier" || pip3 install apksigcopier
ls release.keystore                                     # at repo root, gitignored, alias 'nebula'
```

The keystore store/key password is **not** in the repo — retrieve it from the
maintainer (or the `release-signing` memory) before you start.

---

## Step 1 — Bump version, write notes, commit, tag

In `AndroidManifest.xml` bump **both**:

- `android:versionCode` — integer, +1 every release (F-Droid keys builds on it).
- `android:versionName` — e.g. `4.7.2`.

Then:

- Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (the F-Droid
  "What's New", e.g. `27.txt`).
- Update `CHANGELOG.md`.
- If the audience/positioning changed, update
  `fastlane/metadata/android/en-US/short_description.txt` (F-Droid summary) and
  the README to match.

Commit everything, then tag **that commit**:

```bash
git add -A && git commit -m "Release vX.Y.Z: <summary>"
git tag vX.Y.Z
git push && git push --tags
```

> ⚠️ The tag's tree is exactly what F-Droid builds. Do **not** keep committing to
> `main` after tagging and then build from `main` — that is what broke v4.7.1.
> Step 2 builds from the tag precisely so later `main` commits can't leak in.

---

## Step 2 — Build + verify from the tag (one fail-fast block)

Copy-paste this whole block. It checks out the **tag** into a throwaway worktree,
release-signs, and verifies **both** the signer fingerprint and reproducibility.
It exits non-zero (and prints `FAILED`) if anything is wrong — do not proceed past
a non-zero exit.

```bash
set -e
export ANDROID_HOME="$HOME/Library/Android/sdk"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
EXPECT_FP="878ec6cee21525482bd880c97bde14e1be71a27d581502f7326457daf6693639"
TAG=vX.Y.Z                                  # <-- set this
KSPASS='<release-keystore-password>'        # <-- set this
REPO="$PWD"
WT=/tmp/nebula-release

# Clean checkout of the tag (keystore is gitignored, so copy it in).
rm -rf "$WT"; git worktree prune
git worktree add "$WT" "$TAG"
cp "$REPO/release.keystore" "$WT/release.keystore"
cd "$WT"

# Release-sign. build.sh aborts itself if the signer fingerprint is wrong.
RELEASE_KEYSTORE="$PWD/release.keystore" \
RELEASE_KEY_ALIAS=nebula \
RELEASE_KEYSTORE_PASS="$KSPASS" \
RELEASE_KEY_PASS="$KSPASS" \
TZ=UTC SIGNING_MODE=release ./build.sh

# (a) Signer fingerprint must be the release key.
GOT_FP=$("$APKSIGNER" verify --print-certs Nebula.apk | awk '/SHA-256 digest/{print $NF}')
[ "$GOT_FP" = "$EXPECT_FP" ] || { echo "FAILED: wrong signer $GOT_FP"; exit 1; }

# (b) Reproducible: rebuild unsigned (what F-Droid does), copy the signature on,
#     and require byte-identical + verify.
cp Nebula.apk /tmp/release.apk
TZ=UTC SIGNING_MODE=unsigned ./build.sh >/dev/null
python3 - <<'PY' || exit 1
import apksigcopier, sys
apksigcopier.do_copy('/tmp/release.apk','Nebula.apk','/tmp/sigcp.apk',
                     v1_only=None, exclude=apksigcopier.exclude_meta)
a=open('/tmp/release.apk','rb').read(); b=open('/tmp/sigcp.apk','rb').read()
sys.exit(0 if a==b else (print('FAILED: not reproducible') or 1))
PY
"$APKSIGNER" verify --verbose /tmp/sigcp.apk >/dev/null || { echo "FAILED: verify"; exit 1; }

# Restore the signed APK (the unsigned rebuild overwrote it) and record its hash.
cp /tmp/release.apk Nebula.apk
echo "OK  fingerprint + reproducibility passed"
echo "APK: $WT/Nebula.apk"
shasum -a 256 Nebula.apk
```

If it printed `OK …`, the APK at `/tmp/nebula-release/Nebula.apk` is the artifact
to ship. Keep that shell open (or note the path); the next steps use it.

---

## Step 3 — Publish the GitHub release (BEFORE F-Droid)

> ⚠️ F-Droid CI downloads the APK from the GitHub release **at build time**. If
> the correct asset isn't live first, CI verifies a stale/missing/old file and
> fails. This broke v4.6.8. Always do this step before Step 5.

The asset **must** be named `Nebula.apk` (the `Binaries` URL is
`…/releases/download/v%v/Nebula.apk`). From the worktree:

```bash
cd /tmp/nebula-release
gh release create vX.Y.Z Nebula.apk --repo jordanade/Nebula --title vX.Y.Z --notes "<notes>"
#   …or, if the release already exists:
gh release upload  vX.Y.Z Nebula.apk --repo jordanade/Nebula --clobber

# Confirm the LIVE asset is byte-identical to what you verified.
curl -sL https://github.com/jordanade/Nebula/releases/download/vX.Y.Z/Nebula.apk \
  | shasum -a 256
# ^ must equal the shasum printed at the end of Step 2
```

---

## Step 4 — Tear down the worktree

```bash
cd /Users/jordan/Projects/nebula
rm -f /tmp/nebula-release/release.keystore          # never leave the keystore lying around
git worktree remove --force /tmp/nebula-release
```

---

## Step 5 — F-Droid picks the release up automatically (verify, don't act)

Nebula's fdroiddata metadata has `UpdateCheckMode: Tags` +
`AutoUpdateMode: Version`: the F-Droid **checkupdates bot** detects the new
tag on its own, files its own MR, and an F-Droid maintainer merges it. That's
how v4.7.0 and v4.7.1 shipped (e.g.
[!41455](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/41455) =
"bot: Update Nebula to 26"). **There is nothing to submit manually — do not
file an MR and do not edit the old `add-nebula` fork branch.**

The bot's build downloads `Nebula.apk` from the GitHub release and verifies it
byte-for-byte against its own unsigned rebuild of the tag. That is why Steps
2–3 (verified, reproducible APK live at the Binaries URL) must be complete
before the bot runs — which they are, if you followed this guide in order.

The in-repo mirror `fdroiddata-metadata.yml` syncs itself: the
`sync-fdroid-metadata.yml` workflow fetches upstream master daily and commits
only when it changed. **The release is finished after Step 4 — there is no
follow-up visit.** A "Sync fdroiddata metadata (upstream CurrentVersion
X.Y.Z)" commit appearing on `main` within a day or two is your confirmation
that the bot picked the release up.

If no such commit has appeared after ~a week: check the mirror manually,

```bash
curl -s "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/repository/files/metadata%2Fcom.jordanadema.nebula.yml/raw?ref=master" \
  | grep -E 'CurrentVersion'
```

then search the bot's MRs
(<https://gitlab.com/fdroid/fdroiddata/-/merge_requests?search=nebula>) for a
failed pipeline. A reproducibility failure means the GitHub asset doesn't match
the tag's tree or toolchain — go back to Step 2 in a clean tag worktree and
re-upload. As a last resort, file a **fresh MR against fdroiddata master** with
a manual build entry; the old manual-MR instructions live in this file's git
history (pre-v4.8.0).

---

## One-glance checklist

- [ ] **Step 1** versionCode + versionName bumped; changelog/summary written; commit **tagged** `vX.Y.Z`; pushed
- [ ] **Step 2** Built from the **tag worktree**; block printed `OK` (fingerprint `878ec6ce…` + reproducible)
- [ ] **Step 3** `Nebula.apk` uploaded to the GitHub release; **live** shasum == Step 2 shasum
- [ ] **Step 4** Worktree + copied keystore removed
- [ ] **Step 5** nothing to do — the mirror-sync workflow's "Sync fdroiddata metadata" commit (within a day or two) confirms the bot shipped it
