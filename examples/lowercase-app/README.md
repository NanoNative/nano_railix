# Lowercase CLI App

This is the smallest loadable Creator project:

```text
App -> CLI -> lowercase context.payload.arguments[0] -> context.result -> End
```

Build and open it:

```sh
./mvnw clean verify
```

macOS:

```sh
modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix creator \
  examples/lowercase-app/railix.project.json
```

Linux:

```sh
modules/railix-creator/target/app-image/railix/bin/railix creator \
  examples/lowercase-app/railix.project.json
```

Select the CLI Trigger. Creator automatically sends its committed payload to the rolling-built
application and shows `context.result = "hello railix"`.

Run the same project through the real CLI boundary:

macOS:

```sh
(cd examples/lowercase-app && \
  ../../modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix run "Hello RAILIX")
```

Linux:

```sh
(cd examples/lowercase-app && \
  ../../modules/railix-creator/target/app-image/railix/bin/railix run "Hello RAILIX")
```
