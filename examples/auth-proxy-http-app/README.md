# Auth Proxy HTTP App

This example shows one Railix HTTP app calling another Railix HTTP app:

```text
Client -> Auth Proxy HTTP App -> HTTP Client Step -> Auth HTTP App -> response
```

Start the auth service app first:

```sh
cd ../auth-http-app
../../modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix serve 18081
```

In another terminal, start this proxy app:

```sh
cd ../auth-proxy-http-app
../../modules/railix-creator/target/app-image/railix.app/Contents/MacOS/railix serve 18082
```

Then call the proxy:

```sh
curl -i -X POST http://127.0.0.1:18082/proxy-login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"demo-password"}'
```

The proxy calls `http://127.0.0.1:18081/login`, receives the auth response, and returns that response body/status to its own caller.
