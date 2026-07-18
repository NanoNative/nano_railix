# Spec Files

This folder contains human-readable machine-shaped specifications. They are intentionally YAML/JSON-like, but not final JSON Schema.

Important files:

```text
railix-app.spec.yaml             canonical app plan shape
step-contract.spec.yaml          reusable step contract
operator-contract.spec.yaml      DataWorkbench operator contract
settings-tree.spec.yaml          unified config/secret model
envelope-reply.spec.yaml         generic trigger input and reply output
railix-value.spec.yaml           value model
path-selector-patch.spec.yaml    nested data addressing and patches
run-signal.spec.yaml             observation events
permissions.spec.yaml            permission model
protocol-pack.spec.yaml          protocol extension model
package-manifest.spec.yaml       dependency pack model
```

Examples live in `spec/examples/` and `examples/`.
