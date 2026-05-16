# Railix Sample App (GraalVM Native)

This sample app demonstrates Railix APT (`@Railix`) and builds as a GraalVM native executable.

## Build

From the Railix repo root:

```bash
mvn -DskipTests clean install
```

Then:

```bash
cd src/test/resources/sample-app
mvn -DskipTests -Pnative clean package
./target/sample-app
```

