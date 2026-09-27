# Always-on phone-mic VAD asset

`silero_vad.onnx` is checked into this directory so every Android build uses the same
verified model. It is the Silero VAD 6.2.1 16 kHz op15 model vendored by the repository
in `backend/utils/stt/assets/silero_vad.onnx` (introduced by commit `d4309bc3e9` and
retained by `66ed3fe832`). The Android copy must have this SHA-256:

```text
1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3
```

Do not replace it with a model downloaded from an unpinned URL. If the upstream model
is upgraded, update both copies and this hash in one reviewed change. The Android
adapter uses 256-sample windows at 16 kHz, as required by this model version.
