# Auth HTTP App

This example routes HTTP requests by path, then by method:

```text
App -> HTTP -> Path Switch
                /login -> Login method Switch -> POST -> Check credentials
                /register -> Register method Switch -> POST -> Fixed response
                Otherwise -> Not-found response
```

| Request | Response |
| --- | --- |
| `POST /login` with the demo credentials | `200` with an authenticated response and demo token. |
| `POST /login` with missing or incorrect credentials | `401` with `{"authenticated":false,"error":"unauthorized"}`. |
| `POST /register` | `200` with `{"registered":true,"message":"Dummy registration response."}`. |
| Any other method on `/login` or `/register` | `405` with `{"error":"method_not_allowed"}` and `Allow: POST`. |
| Any other path | `404` with `{"error":"not_found"}`. |

Registration ignores the request body and creates no account.

The Trigger includes Examples for login success, missing/incorrect credentials,
registration, unsupported methods, and an unknown path.

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
../../modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix run
```

Linux:

```sh
../../modules/railix-creator/target/app-image/railix/bin/railix run
```

Then call it from another terminal:

```sh
curl -i -X POST http://127.0.0.1:8080/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"demo-password"}'
```

Call the dummy registration route:

```sh
curl -i -X POST http://127.0.0.1:8080/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"new.user@example.com","password":"demo-password"}'
```
