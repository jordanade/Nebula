#!/usr/bin/env sh
set -eu

root_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$root_dir"

api_level="${API_LEVEL:-35}"
build_tools_version="${BUILD_TOOLS_VERSION:-35.0.0}"
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
    if [ ! -f "$ANDROID_JAR" ]; then
        # Fall back to highest available platform (for F-Droid build server)
        ANDROID_JAR=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -t- -k2 -n | tail -1 || true)
        if [ -z "$ANDROID_JAR" ]; then
            echo "Missing ANDROID_JAR: no android.jar found under $ANDROID_HOME/platforms/" >&2
            exit 1
        fi
    fi
fi

if [ -z "${BUILD_TOOLS:-}" ]; then
    if [ -z "${ANDROID_HOME:-}" ]; then
        echo "Set BUILD_TOOLS or ANDROID_HOME." >&2
        exit 1
    fi
    BUILD_TOOLS="$ANDROID_HOME/build-tools/$build_tools_version"
    if [ ! -d "$BUILD_TOOLS" ]; then
        BUILD_TOOLS=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1 | sed 's:/$::' || true)
        if [ -z "$BUILD_TOOLS" ]; then
            echo "Missing BUILD_TOOLS: no build-tools found under $ANDROID_HOME/build-tools/" >&2
            exit 1
        fi
    fi
fi

AAPT="${AAPT:-$BUILD_TOOLS/aapt}"
DX="${DX:-$BUILD_TOOLS/dx}"
D8="${D8:-$BUILD_TOOLS/d8}"
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
if [ ! -x "$DX" ]; then
    requireExec "$D8" D8
fi
requireExec "$ZIPALIGN" ZIPALIGN

gen_dir=gen
rm -rf "$obj_dir" "$bin_dir" "$gen_dir"
mkdir -p "$obj_dir" "$bin_dir"

# Resources are optional. If res/ exists, generate R.java and include the
# resource table when packaging; otherwise fall back to a manifest-only APK.
res_args=""
if [ -d res ]; then
    mkdir -p "$gen_dir"
    "$AAPT" package -f -m -J "$gen_dir" -S res -M AndroidManifest.xml -I "$ANDROID_JAR"
    res_args="-S res"
fi

# Compile every .java under src/ (and generated R.java) in one pass.
find src "$gen_dir" -name '*.java' 2>/dev/null > "$bin_dir/sources.txt"
# --release 8 produces clean Java 8 class files without JDK 17+ attributes
# that crash older D8 versions (NullPointerException in R8 graph processing)
"$javac_bin" -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" \
    -d "$obj_dir" @"$bin_dir/sources.txt"

if [ -x "$DX" ]; then
    "$DX" --dex --output="$bin_dir/classes.dex" "$obj_dir/"
else
    find "$obj_dir" -name '*.class' > "$bin_dir/classes-d8.txt"
    "$D8" --min-api 21 --lib "$ANDROID_JAR" --output "$bin_dir" @"$bin_dir/classes-d8.txt"
fi

# shellcheck disable=SC2086
"$AAPT" package -f -F "$bin_dir/nebula.unaligned.apk" \
    -M AndroidManifest.xml $res_args -I "$ANDROID_JAR"
(cd "$bin_dir" && "$AAPT" add -f nebula.unaligned.apk classes.dex)

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
