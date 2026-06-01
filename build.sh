#!/usr/bin/env sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$root_dir"

api_level="${API_LEVEL:-23}"
build_tools_version="${BUILD_TOOLS_VERSION:-30.0.3}"
out_apk="${OUT_APK:-Nebula.apk}"
obj_dir=obj
bin_dir=bin
javac_bin="${JAVAC:-javac}"
keytool_bin="${KEYTOOL:-keytool}"

if [ -z "${ANDROID_JAR:-}" ]; then
    if [ -z "${ANDROID_HOME:-}" ]; then
        echo "Set ANDROID_JAR or ANDROID_HOME." >&2
        exit 1
    fi
    ANDROID_JAR="$ANDROID_HOME/platforms/android-$api_level/android.jar"
fi

if [ -z "${BUILD_TOOLS:-}" ]; then
    if [ -z "${ANDROID_HOME:-}" ]; then
        echo "Set BUILD_TOOLS or ANDROID_HOME." >&2
        exit 1
    fi
    BUILD_TOOLS="$ANDROID_HOME/build-tools/$build_tools_version"
fi

AAPT="${AAPT:-$BUILD_TOOLS/aapt}"
DX="${DX:-$BUILD_TOOLS/dx}"
ZIPALIGN="${ZIPALIGN:-$BUILD_TOOLS/zipalign}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS/apksigner}"

requireFile() {
    if [ ! -f "$1" ]; then
        echo "Missing $2: $1" >&2
        exit 1
    fi
}

requireExec() {
    if [ ! -x "$1" ]; then
        echo "Missing or non-executable $2: $1" >&2
        exit 1
    fi
}

requireFile "$ANDROID_JAR" ANDROID_JAR
requireExec "$AAPT" AAPT
requireExec "$DX" DX
requireExec "$ZIPALIGN" ZIPALIGN

rm -rf "$obj_dir" "$bin_dir"
mkdir -p "$obj_dir" "$bin_dir"

"$javac_bin" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -d "$obj_dir" src/com/nebula/NebulaDream.java

"$DX" --dex --output="$bin_dir/classes.dex" "$obj_dir/"

"$AAPT" package -f -F "$bin_dir/nebula.unaligned.apk" \
    -M AndroidManifest.xml -I "$ANDROID_JAR"
zip -q -j "$bin_dir/nebula.unaligned.apk" "$bin_dir/classes.dex"

"$ZIPALIGN" -f 4 "$bin_dir/nebula.unaligned.apk" "$bin_dir/nebula.aligned.apk"

signing_mode="${SIGNING_MODE:-auto}"
if [ "$signing_mode" = auto ]; then
    if [ -n "${RELEASE_KEYSTORE:-}" ]; then
        signing_mode=release
    else
        signing_mode=debug
    fi
fi

case "$signing_mode" in
    release)
        requireExec "$APKSIGNER" APKSIGNER
        if [ -z "${RELEASE_KEYSTORE:-}" ] || [ -z "${RELEASE_KEY_ALIAS:-}" ]; then
            echo "Release signing needs RELEASE_KEYSTORE and RELEASE_KEY_ALIAS." >&2
            exit 1
        fi

        set -- --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS"
        if [ -n "${RELEASE_KEYSTORE_PASS:-}" ]; then
            set -- "$@" --ks-pass "pass:$RELEASE_KEYSTORE_PASS"
        fi
        if [ -n "${RELEASE_KEY_PASS:-}" ]; then
            set -- "$@" --key-pass "pass:$RELEASE_KEY_PASS"
        fi

        "$APKSIGNER" sign "$@" --out "$out_apk" "$bin_dir/nebula.aligned.apk"
        "$APKSIGNER" verify --verbose "$out_apk"
        ;;
    debug)
        requireExec "$APKSIGNER" APKSIGNER
        debug_keystore="${DEBUG_KEYSTORE:-debug.keystore}"
        if [ ! -f "$debug_keystore" ]; then
            "$keytool_bin" -genkeypair -v -keystore "$debug_keystore" \
                -storepass android -alias androiddebugkey -keypass android \
                -keyalg RSA -keysize 2048 -validity 10000 \
                -dname "CN=Android Debug,O=Android,C=US"
        fi
        "$APKSIGNER" sign --ks "$debug_keystore" \
            --ks-pass pass:android --key-pass pass:android \
            --out "$out_apk" "$bin_dir/nebula.aligned.apk"
        "$APKSIGNER" verify --verbose "$out_apk"
        ;;
    unsigned)
        cp "$bin_dir/nebula.aligned.apk" "$out_apk"
        ;;
    *)
        echo "SIGNING_MODE must be auto, release, debug, or unsigned." >&2
        exit 1
        ;;
esac

echo "Built $out_apk ($signing_mode)."
