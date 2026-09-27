# Always-on ASR model assets

Stage 3 expects the following development assets:

```
always_on/models/sensevoice-2024-07-17-int8/model.int8.onnx
always_on/models/sensevoice-2024-07-17-int8/tokens.txt
```

Run `tools/fetch_sensevoice_asr.sh` before building an ASR-enabled development APK.
The model is intentionally not checked into git. Production should replace this
build-time asset flow with a versioned ModelManager/download flow.
