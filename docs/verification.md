# Delivery verification

Verified on 2026-10-24 (Asia/Shanghai), starting from repository commit `642b8b8`.

Environment: Linux, OpenJDK 17.0.20, wrapper-pinned Maven 3.9.6, Spring Boot 4.1.1,
MCP Java SDK BOM 2.0.1, Python 3 standard library. Maven artifact downloads used the
workspace network proxy. No live model credentials or cloud sandbox were used.

## Build and regression gate

```sh
./mvnw clean verify
```

Result: **103 tests, 0 failures, 0 errors, 0 skipped**. Produced the runnable
`target/turnforge.jar`. Existing cancellation, persistence, sandbox protocol, model
adapter and MCP transport tests remain included.

New regression coverage verifies execution deadlines, worker/queue saturation,
inspectable overload errors (including creation with initial input), bounded event
subscribers, handoff without an active execution deadline, recovery backlog at
startup, application-owned tool composition, invalid runtime settings, and context
compaction across steering inside tool exchanges.

## Packaged application gate

```sh
python3 scripts/smoke.py --output-dir target/acceptance
```

Result: **9 scenarios passed**. The script launches actual JAR processes on random
loopback ports, a deterministic Chat Completions protocol fixture, and the example
MCP server as a real stdio subprocess. It exercises the public HTTP API rather than
calling Java service methods directly.

| Scenario | Observed outcome |
| --- | --- |
| Default offline startup and CLI | `Hello` returns `Demo response: Hello`; reusing the session returns the next response |
| Model adapter | Packaged application calls the local HTTP provider and persists the answer |
| MCP tool loop | SDK initializes/discovers/calls the stdio echo server; call and result items are paired |
| JSON/SSE replay | `Last-Event-ID` resumes after the cursor; event identities stay stable |
| Cancellation | An active blocked model request reaches a terminal cancelled turn |
| Deadline | A blocked request reaches a failed turn with `turn execution timed out` |
| Capacity | One worker plus one queued turn exhausts capacity; `503` contains inspectable session/turn IDs |
| External handoff and restart | A waiting approval survives restart, resumes after result submission, accepts identical duplicates and rejects changed duplicates |
| Process crash | An in-flight request fails on recovery without repeating the model request; the session accepts a subsequent turn |

Logs and machine-readable results are written under the chosen output directory.
Use a fresh directory for each run. The local final run used `target/acceptance-final`.
The workflow runs the same build and acceptance gate on Java 17 and Java 21 and
uploads the JAR and evidence as workflow artifacts.

## Scope of this evidence

This verifies the offline runtime and its model/MCP protocol integrations. It does
not evaluate real model answer quality or prove live-provider access. CubeSandbox
coverage uses protocol fixtures; a live cluster was not available. Docker execution
and Java 21 were not tested in the local runtime; Java 21 is included in CI.

The delivery is a single-process runtime. Authentication, tenant isolation,
distributed ownership, semantic compaction and retention/pagination remain outside
this version's operating scope, as documented in the architecture contract.
