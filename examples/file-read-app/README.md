# File Read App

This application reads one explicitly selected YAML file through the ordinary `file.read`
Step and returns its normalized primitive value.

```sh
mvn -q verify
./railix run examples/file-read-app
```

Expected output:

```json
{"value":{"active":true,"name":"Railix"}}
```

`input.json` supplies the path, `railix.flow.json` selects YAML explicitly, and
`value.yaml` is the real file read by the packaged launcher. Missing or rejected files are
ordinary flow outcomes; this example routes all three outcomes to completion.
