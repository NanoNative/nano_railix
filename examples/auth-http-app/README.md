# Auth HTTP App

This example routes HTTP requests by path, then by method, and uses two custom
Java Steps after a successful credential check:

```text
App -> HTTP -> Path Branch
                /login -> Method Branch -> POST -> Check credentials
                                                    Match -> CustomUpper -> CustomLower -> Fixed login response
                                                    Otherwise -> Unauthorized response
                /register -> Method Branch -> POST -> Fixed response
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

Login first checks the exact demo credentials: `alice@example.com` and
`demo-password`. On a match, `CustomUpper` trims and uppercases the email, then
`CustomLower` trims and lowercases it. Both write their result back to
`context.payload.request.body.email`. These transformations happen after the
credential check, so they do not make login case-insensitive. The successful
response uses fixed values for `user` (`alice@example.com`) and `token`
(`railix-demo-token`).

The project has three source documents:

| File | Contents |
| --- | --- |
| [railix.project.json](railix.project.json) | Flow, Step placements and field mappings. |
| [railix.examples.json](railix.examples.json) | Eight saved HTTP Trigger Examples. |
| [railix.custom-steps.json](railix.custom-steps.json) | Java method source for `CustomUpper` and `CustomLower`. |

Keep all three files together when copying this example. The custom methods are
compiled when the project is opened in Creator, built or run from source.
The saved Examples cover login success, missing/incorrect credentials,
registration, unsupported methods, and an unknown path.

Run the following commands from this directory (`examples/auth-http-app`).

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
