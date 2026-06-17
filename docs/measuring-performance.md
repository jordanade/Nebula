# Measuring Performance

Nebula is a GLSurfaceView-based Android dream (screensaver) running on an
NVIDIA Shield at 4K. Because it renders directly via OpenGL ES, the standard
Android frame-stats tooling does not apply. This document explains what works
and what doesn't.

## What does NOT work

### `dumpsys gfxinfo`

```sh
adb -s shield:5555 shell dumpsys gfxinfo com.nebula
```

This reports **0 frames rendered** because gfxinfo tracks the hwui (Canvas /
RenderThread) pipeline. Nebula bypasses hwui entirely — it draws with GLES
into a SurfaceView, so hwui never sees a frame.

### GPU sysfs (`/sys/devices/57000000.gpu/load`, `cur_freq`, etc.)

These files exist on the Shield's Tegra GPU but are **permission-denied**
without root. Not usable over standard adb.

## What works

### App-level SPIKE logs (primary method)

Nebula logs its own performance telemetry to logcat under the tag
`NebulaDream`. Every ~2 seconds it emits a `SPIKE` line:

```sh
adb -s shield:5555 shell logcat -s NebulaDream
```

Example output:

```
NebulaDream: SPIKE cadence=23.5fps gpuWork=74.6ms surface=3839x2160 gas=384x216 gasScale=0.10 hdr=true activeMode=3840x2160@59.94#21 ...
```

Key fields:

| Field | Meaning |
|-------|---------|
| `cadence` | Actual render FPS (frames the GL thread is producing) |
| `gpuWork` | Time in ms the GPU spends on one frame (fence-to-fence) |
| `surface` | Render target resolution |
| `gas` / `gasScale` | Resolution of the nebula gas pass and its scale factor |
| `hdr` | Whether HDR output is active |
| `activeMode` | Display mode the Shield is actually using |

To measure the impact of a change:

1. Deploy the build (`adb install -r Nebula.apk`)
2. Launch the dream (`adb -s shield:5555 shell am start -n com.android.systemui/.Somnambulator`)
3. Wait 5-10 seconds for warm-up
4. Collect ~30 seconds of SPIKE logs
5. Look at `cadence` and `gpuWork` — cadence is the throughput, gpuWork is the per-frame cost

Typical baseline (4K HDR): cadence ~23-24 fps, gpuWork ~65-80 ms.

### FLARE logs

Interleaved with SPIKE lines, `FLARE` lines log lens-flare triggers:

```
NebulaDream: FLARE cell=58.0,73.0 layer=0 mag=0.477
```

These are informational and not performance-relevant.

### SurfaceFlinger (secondary)

```sh
adb -s shield:5555 shell dumpsys SurfaceFlinger --framestats
```

Look for the `SurfaceView - com.nebula/...` line. The first number is total
frames composited. Comparing two snapshots over a known interval gives you the
compositor-side FPS. This confirms frames are reaching the display but does not
break down GPU cost — use SPIKE logs for that.

Useful to confirm the surface format and resolution:

```sh
adb -s shield:5555 shell dumpsys SurfaceFlinger | grep 'activeBuffer.*nebula'
```

Expected: `activeBuffer=[3839x2160:3840,RGBA_FP16]` for HDR mode.

## Quick recipe

```sh
# Deploy, launch, and tail performance logs in one shot:
ANDROID_HOME=/Users/jordan/Library/Android/sdk ./build.sh \
  && adb connect shield:5555 \
  && adb -s shield:5555 install -r Nebula.apk \
  && adb -s shield:5555 shell am start -n com.android.systemui/.Somnambulator \
  && sleep 5 \
  && adb -s shield:5555 shell logcat -s NebulaDream
```

Ctrl-C to stop. The SPIKE lines are the performance data.
