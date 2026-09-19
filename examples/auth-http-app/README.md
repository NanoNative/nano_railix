# Auth HTTP App

This is a small loadable Creator project using the HTTP trigger:

```text
App -> HTTP -> switch context.payload.request.path -> check credentials -> response body/status -> End
```

Open it in Creator:

macOS:

```sh
../../modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix creator \
  railix.project.json
```

Linux:

```sh
../../modules/railix-creator/target/app-image/railix/bin/railix creator \
  railix.project.json
```

Serve it as an HTTP app:

macOS:

```sh
../../modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix serve 8080
```

Linux:

```sh
../../modules/railix-creator/target/app-image/railix/bin/railix serve 8080
```

Then call it from another terminal:

```sh
curl -i -X POST http://127.0.0.1:8080/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"demo-password"}'
```

Missing or incorrect credentials return `401` with `{"authenticated":false,"error":"unauthorized"}`.
