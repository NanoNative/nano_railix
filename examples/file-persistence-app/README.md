# File Persistence App

This whole-flow application writes canonical JSON, reads the complete persisted value, and
deletes the file through ordinary trusted Steps.

```sh
mvn -q verify
./railix run examples/file-persistence-app
```

Expected output:

```json
{"value":{"active":true,"name":"Railix"}}
```

The flow explicitly routes every write, read, and delete outcome. `file.write` uses
`overwrite:true` so repeated runs are deterministic. A successful run leaves neither
`value.json` nor a Railix temporary file behind.
