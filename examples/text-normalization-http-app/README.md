# Text Normalization HTTP App

A small service built entirely from ordinary Steps:

```text
HTTP -> Check route -> Check text type and length -> Normalize -> Response
                 \-> 404                       \-> 400
```

Using the packaged `RAILIX` launcher from the root README, open this project from the
repository root:

```sh
"$RAILIX" creator examples/text-normalization-http-app/railix.project.json
```

Creator starts the development application on `127.0.0.1:8080`. Change the HTTP
Trigger's Port if another application uses it. The included Examples inject request
values directly, without making HTTP requests, and cover valid, blank, missing,
wrong-type, unknown-route and wrong-method inputs.

```sh
curl -i http://127.0.0.1:8080/normalize \
  -H 'Content-Type: application/json' \
  -d '{"text":"  Hello   RAILIX  "}'
```

This returns HTTP 200 with `{"text":"hello railix"}`. Non-string, missing or blank
text returns HTTP 400 with `error: "invalid_text"`. Any other route or method returns
HTTP 404 with `error: "not_found"`.

The first text condition uses **Is Type: String**. Only after it matches does the
second AND condition normalize whitespace and check length. This is normal Choice
short-circuiting, not special HTTP validation.

For an editing check, select a valid Example, remove the type guard and change its
text to a number. Creator should explain the failed text operation on the Choice.
Restore the guard to route that input to the intentional HTTP 400 response instead.
Example execution errors are visible; passing Examples do not prove all possible
requests safe or constitute response assertions.

To serve without Creator, stop Creator first and run:

```sh
(cd examples/text-normalization-http-app && "$RAILIX" run)
```
