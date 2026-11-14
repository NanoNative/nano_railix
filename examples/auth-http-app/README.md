# Auth HTTP App

This example routes HTTP requests by path. Login and registration also check the
method; custom Java Steps calculate order quotes:

```text
App -> HTTP -> Path Branch
                /login -> Method Branch -> POST -> Check credentials
                                                    Match -> CustomUpper -> CustomLower -> Fixed login response
                                                    Otherwise -> Unauthorized response
                /register -> Method Branch -> POST -> Fixed response
                /order -> OrderValue -> Order quote response
                Otherwise -> Not-found response
```

| Request | Response |
| --- | --- |
| `POST /login` with the demo credentials | `200` with an authenticated response and demo token. |
| `POST /login` with missing or incorrect credentials | `401` with `{"authenticated":false,"error":"unauthorized"}`. |
| `POST /register` | `200` with `{"registered":true,"message":"Dummy registration response."}`. |
| `POST /order` with valid order fields | `200` with priced items, discount, shipping, tax and total. |
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

The `OrderValue` custom Step reads `items`, `discountPercent`, `taxPercent` and
`express` from `context.payload.request.body`. It validates the items and
percentages, applies the discount, adds shipping and calculates tax. Its result is
written to `context.body`, which the HTTP Trigger returns as the response body.
The `/order` route currently checks only the path and goes directly to this Step.

The project has three source documents:

| File | Contents |
| --- | --- |
| [railix.project.json](railix.project.json) | Flow, Step placements and field mappings. |
| [railix.examples.json](railix.examples.json) | Saved HTTP Trigger Examples, including `order-quote`. |
| [railix.custom-steps.json](railix.custom-steps.json) | Java method source for `CustomUpper`, `CustomLower` and `OrderValue`. |

Keep all three files together when copying this example. The custom methods are
compiled when the project is opened in Creator, built or run from source.
The saved Examples cover login success, missing/incorrect credentials,
registration, unsupported methods, an unknown path, and an order quote.

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

Build and run the saved project as a standalone HTTP app:

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

Calculate an order quote with 20 items:

```sh
curl -i 'http://127.0.0.1:8080/order' \
  -H 'Content-Type: application/json' \
  --data-binary '{
    "items": [
      {"sku": "ITEM-01", "quantity": 1, "unitPrice": 1.00},
      {"sku": "ITEM-02", "quantity": 1, "unitPrice": 2.00},
      {"sku": "ITEM-03", "quantity": 1, "unitPrice": 3.00},
      {"sku": "ITEM-04", "quantity": 1, "unitPrice": 4.00},
      {"sku": "ITEM-05", "quantity": 1, "unitPrice": 5.00},
      {"sku": "ITEM-06", "quantity": 1, "unitPrice": 6.00},
      {"sku": "ITEM-07", "quantity": 1, "unitPrice": 7.00},
      {"sku": "ITEM-08", "quantity": 1, "unitPrice": 8.00},
      {"sku": "ITEM-09", "quantity": 1, "unitPrice": 9.00},
      {"sku": "ITEM-10", "quantity": 1, "unitPrice": 10.00},
      {"sku": "ITEM-11", "quantity": 1, "unitPrice": 11.00},
      {"sku": "ITEM-12", "quantity": 1, "unitPrice": 12.00},
      {"sku": "ITEM-13", "quantity": 1, "unitPrice": 13.00},
      {"sku": "ITEM-14", "quantity": 1, "unitPrice": 14.00},
      {"sku": "ITEM-15", "quantity": 1, "unitPrice": 15.00},
      {"sku": "ITEM-16", "quantity": 1, "unitPrice": 16.00},
      {"sku": "ITEM-17", "quantity": 1, "unitPrice": 17.00},
      {"sku": "ITEM-18", "quantity": 1, "unitPrice": 18.00},
      {"sku": "ITEM-19", "quantity": 1, "unitPrice": 19.00},
      {"sku": "ITEM-20", "quantity": 1, "unitPrice": 20.00}
    ],
    "discountPercent": 10,
    "taxPercent": 18,
    "express": false
  }'
```

Curl sends a POST with these order fields as the JSON request body. The HTTP
Trigger exposes them under `context.payload.request.body` for the Step's input
mappings. No login credentials are required for this route.

The response contains `valid: true`, all 20 priced items and
`shippingMode: "standard"`, with these amounts:

| Field | Expected value |
| --- | ---: |
| `subtotal` | `210.00` |
| `discount` | `21.00` |
| `shipping` | `0.00` |
| `tax` | `34.02` |
| `total` | `223.02` |
