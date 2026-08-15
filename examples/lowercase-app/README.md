# Lowercase CLI App

This is the smallest loadable Creator project:

```text
App -> CLI -> lowercase context.payload.arguments[0] -> context.result -> End
```

Build and open it:

```sh
./mvnw clean verify
./railix creator examples/lowercase-app/railix.project.json
```

Select the CLI Trigger. Creator automatically sends its committed payload to the rolling-built
application and shows `context.result = "hello railix"`.

Run the same project through the real CLI boundary:

```sh
cd examples/lowercase-app
../../railix run "Hello RAILIX"
```
