# ✅ DONE — TICKET-001: Current `Config.load()` baseline

> **Status:** ✅ DONE · **Priority:** P0

## Summary

Railix has `org.nanonative.railix.config.Config#load()` but it is **not** Nano-equivalent config compilation.

## What Railix does today

- Loads classpath `railix.properties`.
- Loads external properties from `RAILIX_CONFIG_FILE` / `-Drailix_config_file`.
- Overlays `RAILIX_*` env vars and `railix.*` / `railix_*` system properties.
- Normalizes keys via `Names.sanitize(...)`.

## What Nano does that Railix does not

From Nano `readConfigs(...)` + `readConfigFiles(...)`:
- deterministic directory scanning for `application.properties`
- profile cascade `application-<profile>.properties` with dynamic profile discovery
- explicit overlay precedence (ENV < `-D` < CLI args < programmatic)
- `${key}` / `${key:fallback}` placeholder resolution
- second-pass profile cascade after overlays

## References

- Nano: `/Users/yuna/projects/nano/src/main/java/org/nanonative/nano/core/NanoBase.java` (`readConfigs`)
- Nano: `/Users/yuna/projects/nano/src/main/java/org/nanonative/nano/helper/NanoUtils.java` (`readConfigFiles`)
- Railix: `src/main/java/org/nanonative/railix/config/Config.java`
