# Settings and Secrets

Railix II uses one settings system for config and secrets.

## SettingsTree

Everything configurable is a setting:

```text
settings.http.port
settings.database.user
settings.database.password
settings.feature.newCheckout
```

Each setting has metadata:

```yaml
type: string
required: true
secret: false
encrypted: false
source: app.yaml
visibility: normal
audit: on-read
```

## Secret settings

A secret is a protected setting.

```yaml
database:
  password:
    type: string
    secret: true
    encrypted: true
    value: ENC[AES256_GCM,data:...,type:str]
```

Secret behavior:

- hidden in UI and logs
- materialized only on access
- audited automatically
- never placed in cache keys
- never exported as plain text
- inherited with sticky secret metadata

## Secret-ness is sticky

If a setting is marked `secret: true`, child overrides remain secret unless a trusted editor explicitly changes the definition.

## Sources

Settings can come from:

```text
packaged defaults
environment profile file
plain properties/YAML file
encrypted SOPS-like file
environment variables
CLI args
manual run input
```

## Resolution precedence

```text
packaged defaults
  -> environment defaults
    -> settings file
      -> encrypted settings file
        -> environment variables
          -> JVM system properties
            -> CLI args
              -> manual run input
```

Current launcher-owned override syntax:

```text
environment variable: RAILIX_SETTING__settings__app__mode=prod
JVM property: -Drailix.setting.settings.app.mode=prod
CLI argument: --set settings.app.mode=prod
```

The current launcher treats env, JVM property, and `--set` override values as strings.
On the currently proven macOS app-image surface, JVM properties can also be forwarded through `JAVA_TOOL_OPTIONS`.
On the currently proven packaging-script surface, builder-owned packaged defaults and profiles can replace the baked-in packaged resources through `APP_SETTINGS_DIR`.

## SOPS-like model

Railix II can support encrypted settings files inspired by SOPS:

```text
settings/app.yaml
settings/dev.sops.yaml
settings/stage.sops.yaml
settings/prod.sops.yaml
```

Developers may have local dev keys.
CI/CD may have prod keys.
Developers can build with encrypted prod references without seeing prod values.

## Access model

A step contract requests settings:

```yaml
settings:
  requested:
    - settings.database.user
    - settings.database.password
```

The app/flow/group/step grants access:

```yaml
settings:
  grants:
    - settings.database.user
    - settings.database.password
```

Runtime audits actual materialization of secret values through `setting.read` signals.
