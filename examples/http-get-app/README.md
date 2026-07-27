# HTTP GET app

This committed flow calls a local JSON endpoint through `http.get` and returns
the normalized body plus its HTTP status.

Start the Java development server from the repository root:

```sh
jwebserver -b 127.0.0.1 -p 18082 -d examples/http-get-app/server
```

In another terminal, run the complete flow:

```sh
./railix run examples/http-get-app
```

Expected output:

```json
{"body":{"active":true,"name":"Railix"},"status":200}
```
