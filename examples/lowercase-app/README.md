# Lowercase App

This is the smallest complete Railix application: one flow input, one named Step, one
explicit outcome transition, and one flow output.

```sh
mvn -q verify
./railix run examples/lowercase-app
```

Expected output:

```json
{"text":"hello railix"}
```

Files:

- `railix.flow.json` defines the flow and all mappings.
- `input.json` is the invocation input used by the public launcher.
- `text.lowercase` is provided by `modules/railix-stdlib`.
