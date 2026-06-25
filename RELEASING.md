# Releasing Nebula

This is the end-to-end procedure for cutting a versioned release and getting it
onto F-Droid. Follow it exactly — two real releases were broken by skipping
steps here (v4.7.0 was accidentally **debug-signed**; v4.6.8's first CI run
failed because the signed APK was uploaded *after* CI ran).

For one-off local builds (debug-signed, just to run on a device), see
[README.md → Build from source](README.md#build-from-source). This document is
only for **public releases** that must be reproducible and signed with the
release key.

## The two hard requirements

A Nebula release APK must satisfy both, or F-Droid will reject it:

1. **Signed with the release key**, certificate SHA-256
   `878ec6cee21525482bd880c97bde14e1be71a27d581502f7326457daf6693639`
   (`CN=Jordan Adema`). This is the value in `AllowedAPKSigningKeys` in the
   fdroiddata metadata. Anything else — especially the auto-generated **debug**
   key (`CN=Android Debug`) — is wrong and also breaks upgrades for anyone who
   already installed a release build.
2. **Reproducible**: when F-Droid's CI rebuilds the source unsigned and copies
   your signature onto it, the result must be byte-identical and verify. This
   is why `build.sh` signs with `--alignment-preserved true` (see `build.sh`)
   — do not remove that flag or sign the APK with any other tool.

## Prerequisites

- SDK with `build-tools;35.0.0` and `platforms;android-35` (see the README).
- `release.keystore` at the repo root (gitignored, **never** committed).
  Alias `nebula`. The store/key password is held by the maintainer and is
  **not** stored in this repo — retrieve it before releasing.
- `apksigcopier` for the reproducibility check: `pip3 install apksigcopier`.

## Step 1 — Bump the version

In `AndroidManifest.xml` bump both:

- `android:versionCode` — integer, +1 every release (F-Droid keys builds on it).
- `android:versionName` — e.g. `4.7.0`.

Add a release note for F-Droid's "What's New" at
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (e.g. `25.txt`),
and update `CHANGELOG.md`. If the app's audience changed, update
`fastlane/metadata/android/en-US/short_description.txt` (this is the F-Droid
summary) and the README to match.

Commit these, then tag the commit `vX.Y.Z`. **The tag commit is the one F-Droid
builds** — its tree must be exactly what you release.

## Step 2 — Build and RELEASE-sign

> ⚠️ The trap that broke v4.7.0: `SIGNING_MODE` defaults to `auto`, which
> **debug-signs** when `RELEASE_KEYSTORE` is unset. Always pass
> `SIGNING_MODE=release` *and* the keystore vars for a release.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"

RELEASE_KEYSTORE="$PWD/release.keystore" \
RELEASE_KEY_ALIAS=nebula \
RELEASE_KEYSTORE_PASS='<release-keystore-password>' \
RELEASE_KEY_PASS='<release-keystore-password>' \
TZ=UTC SIGNING_MODE=release ./build.sh
```

`TZ=UTC` is required for reproducibility (aapt writes timestamps). This produces
a release-signed `Nebula.apk`.

## Step 3 — Verify BEFORE uploading

Both checks must pass. Do not upload an APK that fails either.

```bash
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"

# (a) Correct signer? Must print the release key fingerprint.
"$APKSIGNER" verify --print-certs Nebula.apk | grep -i "SHA-256 digest"
# expect: 878ec6cee21525482bd880c97bde14e1be71a27d581502f7326457daf6693639
```

```bash
# (b) Reproducible? Build the unsigned APK F-Droid would build, copy the
#     release signature onto it, and verify — exactly what F-Droid CI does.
cp Nebula.apk /tmp/release.apk
TZ=UTC SIGNING_MODE=unsigned ./build.sh           # overwrites Nebula.apk (unsigned)
python3 - <<'PY'
import apksigcopier
apksigcopier.do_copy('/tmp/release.apk', 'Nebula.apk', '/tmp/sigcp.apk',
                     v1_only=None, exclude=apksigcopier.exclude_meta)
a = open('/tmp/release.apk','rb').read(); b = open('/tmp/sigcp.apk','rb').read()
print('round-trip:', 'IDENTICAL' if a == b else 'DIFFER  <-- NOT reproducible')
PY
"$APKSIGNER" verify --verbose /tmp/sigcp.apk      # must say "Verifies"
```

If the round-trip differs or verification fails, the build is not reproducible —
stop and fix it (usually a tool-version or `--alignment-preserved` issue) rather
than uploading. Then restore the signed APK: `cp /tmp/release.apk Nebula.apk`.

## Step 4 — Publish the GitHub release (do this BEFORE F-Droid)

> ⚠️ The trap that broke v4.6.8's first CI run: the F-Droid CI downloads the APK
> from the GitHub release at build time. If you trigger F-Droid CI before the
> correct APK is live, it verifies against a stale/missing/old asset and fails.

1. Create/locate the GitHub release for the tag `vX.Y.Z`.
2. Upload the verified `Nebula.apk` (from Step 3) as the release asset. The
   `Binaries` URL in the fdroiddata metadata is
   `…/releases/download/v%v/Nebula.apk`, so the asset **must** be named
   `Nebula.apk`.
3. Confirm the live asset matches what you verified:
   `curl -sL <asset-url> | shasum -a 256`.

## Step 5 — Update the F-Droid fdroiddata MR

The fork is checked out at `/private/tmp/fdroiddata` (remote
`jadema1/fdroiddata`, branch `add-nebula`); MR is
[fdroid/fdroiddata!40882](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/40882).
Mirror copy of the metadata lives in this repo at `fdroiddata-metadata.yml`.

Add a build entry to `metadata/com.jordanadema.nebula.yml` and bump
`CurrentVersion`/`CurrentVersionCode`:

```yaml
  - versionName: 4.7.0
    versionCode: 25
    commit: <FULL 40-char commit hash of the vX.Y.Z tag>
    output: Nebula.apk
    prebuild: sdkmanager "platforms;android-35" "build-tools;35.0.0"
    build: TZ=UTC SIGNING_MODE=unsigned ./build.sh
```

> ⚠️ Use the **full 40-char commit hash**, never a short hash, tag, or branch —
> the maintainer (linsui) requires this.

Commit and push to `add-nebula`; this re-triggers the MR pipeline. Watch it go
green (the `fdroid build` job must log
`compared built binary to supplied reference binary successfully`). Poll with:

```bash
curl -s "https://gitlab.com/api/v4/projects/jadema1%2Ffdroiddata/pipelines?ref=add-nebula&order_by=id&sort=desc&per_page=1"
```

## Checklist

- [ ] versionCode/versionName bumped; changelog + summary updated
- [ ] Commit tagged `vX.Y.Z`
- [ ] Built with `SIGNING_MODE=release` (NOT debug)
- [ ] Signer fingerprint == `878ec6ce…f6693639`
- [ ] Reproducibility round-trip IDENTICAL and `apksigner verify` passes
- [ ] Correct `Nebula.apk` uploaded to the GitHub release
- [ ] fdroiddata MR updated with **full** commit hash; pipeline green
