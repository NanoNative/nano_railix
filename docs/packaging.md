# Packaging

Railix II packages apps with Java first.

## Day-one packaging

Use:

```text
jlink
jpackage
```

JARs are intermediate artifacts only.

Current proven packaging contract:

```text
jlink runtime image smoke
jpackage app-image launcher smoke
creator app-image launcher smoke through railix package --mode creator on the current host
local macOS unsigned pkg artifact inspection
builder-emitted packaged settings injection through APP_SETTINGS_DIR on the current packaging scripts
railix package driver through sh tools/railix.sh package with explicit packaged-settings arguments
opt-in success-only JSON package report through --report
default app-image target when --type is omitted
narrow railix.app.yaml ownership for app name, app dependency modules, and packaged defaults/profile staging
```

Not proven yet:

```text
installer execution success
signing or notarization
cross-host packaging matrix
repo-root-free packaged Creator launch
packaged app-owned UI/static-assets mode beyond the Creator shell
```

## Build profiles

A build profile can eventually decide what defaults and dependencies are included.
Today, the proven `railix package` boundary owns direct module selection plus packaged defaults/profile selection:

```text
dev
test
stage
prod
customer-a
customer-b
```

Current proven package command examples:

```bash
sh tools/railix.sh package \
  --type jlink \
  --dest build/image-dev \
  --report build/reports/package-jlink.json \
  --defaults settings/defaults.json \
  --packaged-profile dev=settings/profiles/dev.json

sh tools/railix.sh package \
  --dest build/package \
  --runtime-image build/image-dev \
  --report build/reports/package-app-image.json \
  --defaults settings/defaults.json \
  --packaged-profile dev=settings/profiles/dev.json

sh tools/railix.sh package \
  --type pkg \
  --dest build/installer \
  --app-image-dir build/package \
  --report build/reports/package-pkg.json \
  --defaults settings/defaults.json \
  --packaged-profile dev=settings/profiles/dev.json

sh tools/railix.sh package \
  --app path/to/railix.app.yaml \
  --profile dev \
  --dest build/package \
  --runtime-image build/image-dev \
  --report build/reports/package-from-app.json

sh tools/railix.sh package \
  --mode creator \
  --dest build/package-creator \
  --runtime-image build/image-creator \
  --report build/reports/package-creator-app-image.json
```

Current proven app-spec ownership is intentionally narrow:

```text
app.name -> packaged app name and report appName
app.dependencies[*].id -> packaged module set derived for jlink/jpackage builds
app.profiles -> declared selectable packaged profile names only
app.settings.defaults -> packaged defaults source resolved relative to railix.app.yaml
settings/profiles/<name>.json -> packaged profile resource convention for --profile <name>
declared profiles auto-package when --app is used without an explicit single build profile
```

Not proven at that command boundary yet:

```text
full railix.app.yaml ownership beyond app name, dependency module ids, and packaged settings metadata
dependency resolution or lockfile generation beyond direct module ids
signing or notarization flags
cross-host installer targets
YAML-to-packaged-JSON settings conversion for authoring-side settings sources
```

Current proven package report contract:

```text
success-only JSON
explicit launcher mode: headless or creator
absolute dest/artifact/launcher paths
type-specific artifact fields only
narrow embedded settings evidence: defaultsEmbedded + profileNames
no report file on tested failure paths
```

Example report shape:

```json
{
  "schemaVersion": 1,
  "type": "app-image",
  "mode": "headless",
  "appName": "RailixII",
  "destDir": "/absolute/output/dir",
  "artifacts": {
    "runtimeImageDir": "/absolute/runtime/image",
    "appImageDir": "/absolute/output/dir/RailixII.app",
    "launcherPath": "/absolute/output/dir/RailixII.app/Contents/MacOS/RailixII"
  },
  "embeddedSettings": {
    "defaultsEmbedded": true,
    "profileNames": ["dev"]
  }
}
```

## Runtime configuration

Current proven launcher contract:

```text
packaged defaults from classpath:/railix/settings/defaults.json when present
profile defaults from classpath:/railix/settings/profiles/<name>.json via --profile <name>
builder-emitted packaged defaults and profiles can replace those packaged resources during script-driven packaging through APP_SETTINGS_DIR
explicit settings overlays through repeatable --settings
environment overrides through RAILIX_SETTING__<path-with-__-separators>
JVM system property overrides through -Drailix.setting.<railix.path>=<value> on JVM-driven launch surfaces
current macOS app-image launcher forwarding for those JVM properties through JAVA_TOOL_OPTIONS
CLI string overrides through repeatable --set <railix.path=value>
```

Example:

```bash
railix-app --profile dev --settings settings/customer-override.json
RAILIX_SETTING__settings__app__mode=stage railix-app --profile dev --set settings.app.mode=prod
java -Drailix.setting.settings.app.mode=prod --module-path ... --module railix.kernel/dev.nanonative.railix.kernel.runtime.BuiltRailixAppMain ...
JAVA_TOOL_OPTIONS='-Drailix.setting.settings.app.mode=prod' /Applications/RailixII.app/Contents/MacOS/RailixII --profile dev ...
```

Not proven at the launcher boundary yet:

```text
encrypted settings files
manual run input overlays
direct native app-image/pkg argv forwarding for -D JVM property flags
```

## Modes

```text
headless (current proven packaged mode)
creator (current proven packaged mode on the current host through explicit --repo-root)
app-owned-ui (not implemented yet in the repo runtime/asset surface)
service
```

## Future native target

The desired future native path is `javan`, not GraalVM.

To keep code javan-friendly:

- avoid reflection
- avoid dynamic class loading
- avoid large framework assumptions
- prefer records and explicit APIs
- keep the kernel small
- keep IO/protocol complexity in packs
