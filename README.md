# Samsung Health Step Boost

A Vector / LSPosed (Xposed) module for **rooted** Samsung phones that changes the step data Samsung Health records. It was built for a friendly "anything goes" step challenge between friends who knew about it.

It does two things:

- **Boost**: multiplies the steps you actually walk (default ×2).
- **Simulate**: adds walking minutes while the phone sits still.

Both feed Samsung Health's own pedometer pipeline, the same path the phone's step sensor uses. The steps show up as normal phone-recorded steps in the daily total, Daily activity and Together challenges. Steps imported through Health Connect don't count in challenges, which is why this hooks Samsung Health directly.

> ✅ **Tested on Samsung Health 7.1.0.**

## How it works

Samsung Health saves steps in one-minute buckets (`PedometerSContextManager$SContextPedometerData`). The phone's sensor hub and the passive health-services feed both build one per minute, and hand it to the real-time data manager (`iyp.m(...)`), which writes it with `makeHealthData()`.

| Feature | Hook | What it does |
|---|---|---|
| Boost | `SContextPedometerData.makeHealthData()` (after) | Rewrites the saved record: steps × multiplier, with distance, calories and speed scaled to match. The original object is left alone, so a minute saved twice is never boosted twice. |
| Simulate | Background thread in the `:remote` process (where `PedometerService` runs) | Once a minute, builds a bucket of walking steps and passes it to `iyp.g().m(...)`, the same call the real sensor uses. |

**Keeping the numbers believable:**
- Boosted minutes are capped (default 180 steps/min) and are never lowered below the real count.
- Simulated minutes vary ±12% around the chosen pace and are capped below 230 steps/min. Samsung Health logs and reports minutes with 270 or more steps.
- Simulated distance uses a 0.72 m stride and about 0.04 kcal per step. Speed is calculated with Samsung Health's own helper.

## Requirements

- Rooted Samsung phone with Zygisk, plus [Vector](https://github.com/JingMatrix/Vector) or LSPosed
- Samsung Health 7.1.0.x
- Android SDK (build-tools 36.0.0 and platform android-35) and JDK 17 or newer, to build

## Build and install

```bash
./build.sh                       # produces build/StepBoost.apk (no Gradle needed)
adb install -r build/StepBoost.apk
```

Then, in the Vector/LSPosed manager:
1. Enable **Step Boost for Samsung Health**.
2. Make sure **Samsung Health** is ticked in its scope.
3. Force-stop Samsung Health, or reboot.

`build.sh` compiles against small stubs of the Xposed API in `stubs/`. They're only used for compiling and aren't packaged, because the framework provides the real classes at runtime.

## Settings

Everything is controlled through `settings` keys over adb. There's no app UI, and changes take effect within about a minute without a restart.

| Key | Default | Meaning |
|---|---|---|
| `shealth_step_mult` | `2.0` | Multiplier for real walking. `1` turns boosting off. |
| `shealth_step_max_spm` | `180` | Upper limit for a boosted minute. |
| `shealth_sim_spm` | `0` (off) | Simulated steps per minute. `0` stops the simulation. |
| `shealth_sim_daily_max` | `8000` | Stop simulating after this many fake steps in a day. |

```bash
# simulate ~110 steps/min until 10,000 fake steps today
adb shell settings put global shealth_sim_daily_max 10000
adb shell settings put global shealth_sim_spm 110

# stop simulating
adb shell settings put global shealth_sim_spm 0

# triple real steps
adb shell settings put global shealth_step_mult 3
```

At 110 steps/min, 10,000 simulated steps takes about 90 minutes.

## Checking that it works

Vector writes module logs to `/data/adb/lspd/log/modules_*.log`:

```bash
adb shell su -c 'grep -h StepBoost /data/adb/lspd/log/modules_*.log | tail'
```

```
StepBoost: hooked com.sec.android.app.shealth:remote
StepBoost[sim]: +106 steps at 1793595060000 (today 406/8000)
StepBoost: 95 -> 180 steps (x2.0, cap 180/min)
```

## Fair use

This is for fun on your own device, in challenges where everyone is in on the joke. Don't use it to claim rewards, discounts, insurance benefits or workplace wellness incentives that depend on real activity. That's fraud, not a prank.

Not affiliated with or endorsed by Samsung. "Samsung Health" is a trademark of Samsung Electronics.

## License

[GNU General Public License v3.0](LICENSE)
