# Contributing

Use Java 17 or newer. The Maven wrapper pins Maven; no global Maven installation is required.

```sh
./mvnw verify
python3 scripts/smoke.py
```

The first command runs unit, lifecycle, HTTP and MCP transport tests and builds the executable JAR.
The second starts the actual JAR with a local model protocol fixture and MCP subprocess, exercises
its public API, restarts it, and verifies crash recovery. Both commands must pass before a change
is ready for review. No credentials or cloud services are needed.

Keep `core` independent of Spring. Put deployment configuration in `config`, HTTP transport in
`web`, and provider-specific code in its adapter. A new side-effecting tool must define argument
and result validation, cancellation behavior, and idempotency expectations. Persist intent before
dispatch and never automatically replay uncertain effects. See [architecture](docs/architecture.md).

If the HTTP surface changes, update `api/openapi.yaml`, HTTP tests, and the README example together.
Describe the concrete behavior fixed, relevant compatibility changes, and validation in the PR.
Use a focused regression test for a correctness fix. Do not commit credentials, session snapshots,
or actual conversation/model reasoning data.
