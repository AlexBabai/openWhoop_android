# OpenWhoop Android

OpenWhoop Android is a native Android client for syncing WHOOP 4 heart-rate data locally.

## Features

- Jetpack Compose UI using Material 3 expressive theming.
- Native Android BLE scan/connect flow for WHOOP straps.
- WHOOP Gen 4 protocol constants and packet framing ported from [`AlexBabai/openwhoop`](https://github.com/AlexBabai/openwhoop).
- Realtime HR streaming through WHOOP command `ToggleRealtimeHr` (`0x03`).
- Initial history-sync command sequence for WHOOP 4 (`hello_harvard`, `set_time`, `get_name`, `enter_high_freq_sync`, `history_start`).
- HR extraction from realtime and historical WHOOP packets.
- Android Health Connect write path for `HeartRateRecord`.
- Standard Bluetooth Heart Rate Service fallback for compatible WHOOP firmware/devices, informed by [`megablocks/openwhoop`](https://github.com/megablocks/openwhoop).

## Build

```sh
./gradlew assembleDebug
```

## Validation

```sh
./gradlew lintDebug
```

An emulator can validate app startup and UI flows. A physical WHOOP strap is required to validate the BLE connection and real HR sync end to end.
