# Real-time audio capture on Portal — why the visualizer can't truly "react to live audio"

**Finding (verified on a Portal+ gen-1, API 28, 2026-06-27):** a real-time audio reactor that follows
the actual playing music is **not achievable on Meta Portal for a sideloaded app**. Both capture paths
Android offers are blocked or degraded by the device. The visualizer therefore ships with a smooth
**synthetic** animation by default (it animates whenever a media session is playing), and the live
reactor is an **off-by-default, clearly-labelled experimental** option.

This document records why, so we don't keep re-attempting it.

## Path 1 — output mix via `Visualizer(0)`: BLOCKED

The clean approach is `android.media.audiofx.Visualizer` on audio session `0`, which captures the
device's mixed audio output and exposes its FFT. On Portal it fails at construction:

```
E visualizers-JNI: Visualizer initCheck failed -3
E Visualizer-JAVA: Error code -3 when initializing Visualizer.
```

`-3` is `ERROR_INVALID_OPERATION`. Capturing the **global output mix** (session 0) requires the
privileged, signature-level permission `android.permission.CAPTURE_AUDIO_OUTPUT`, which is **not
grantable to a sideloaded app**. `RECORD_AUDIO` alone is not sufficient for the system mix. There is
also no public way to obtain another app's (e.g. Spotify's) audio **session id** to attach a
per-session `Visualizer` to it — `MediaController` does not expose it.

## Path 2 — microphone via `AudioRecord`: WORKS, BUT LAGGY/CHOPPY

The fallback is to listen to the **microphone**, which hears the music coming out of the device's own
speakers, and run a small Goertzel filter bank over the PCM to get per-band energy (`SoundReactor.kt`).
`AudioRecord(MIC, 44100, MONO, PCM_16BIT)` **does** initialize and start, and **does** read frames — so
the bars genuinely move. But the Portal's always-on assistant owns the single near-field mic and
**preempts our stream constantly**:

```
W AudioRecord: dead IAudioRecord, creating a new one from obtainBuffer()
E         : Request requires com.facebook.alohasdk.permission.RECORD_AUDIO_PRIVILEGED
V AudioPolicyManagerCustom: startInput(...) stopping silenced input 5238
V AudioPolicyManagerCustom: startInput(...) this is NOT a virtual device nor privileged input stream
```

Our (non-privileged) input is repeatedly **silenced and killed**, then we recreate it, in a loop. That
churn is exactly the **lag / choppiness / input latency** observed on screen — the band data arrives in
bursts with multi-hundred-millisecond gaps. The far-field beamformed mic array and uninterrupted
capture are behind `com.facebook.alohasdk.permission.RECORD_AUDIO_PRIVILEGED` (Meta-signed,
unavailable to us). View-side easing smooths the *motion* but cannot fill the data gaps.

This matches the broader Portal mic constraint documented for the Immortal launcher: a sideloaded app
only reaches the single near-field handset mic, which is shared with the device's always-on listeners.

## What we ship instead

- **Default:** the synthetic visualizer (`NowPlayingVisualizerView`, `MiniEqualizerView`). It animates
  whenever a media session reports `STATE_PLAYING`, so it's lively and perfectly smooth, and conveys
  "music is playing" without needing the audio samples. New **Spectrum** style (mirrored bars +
  reflection) plus Waves / Rings / Constellation / Prism.
- **Experimental opt-in:** `SoundReactor` (mic + Goertzel), `RECORD_AUDIO`, off by default, hidden under
  **Settings → Labs** in the control panel (not on the Now Playing or Screensaver tabs). Laggy on
  Portal. Kept because it may behave better on non-Portal hardware running this app, and because it
  self-disables (guarded `start()` returns `false`) so it can never crash the host.

## If revisiting

- Don't retry `Visualizer(0)` — it's a hard permission wall, not a tuning problem.
- A per-session `Visualizer` would need the playing app's audio session id, which isn't exposed.
- The only way to get smooth, uninterrupted capture is the privileged mic permission we can't hold.
- Net: treat "music is playing" (from the media session) as the reactive signal, not the waveform.
