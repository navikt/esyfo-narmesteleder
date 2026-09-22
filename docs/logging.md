# Application logging

Migrated code creates its logger through `applicationLogger(ownerClass)`. This keeps
the class-specific SLF4J name and the existing Logback and tracing configuration.

```kotlin
private val logger = applicationLogger(MyService::class.java)

logger.event(lookupFailed, failureDetails, safeCause)
logger.info("Lookup completed", mapOf("result_count" to resultCount))
```

WARN and ERROR messages are named, typed `Event` definitions. The definition owns
the level, message and operation; the call supplies its typed context and an
optional reviewed cause. INFO and DEBUG diagnostics use the same logger directly.
Do not use native SLF4J or `println` in migrated application code.

The current migration covers `altinntilganger`, `PrincipalAccessValidator`, its
`SystemUserAccessRejection` definition and the extracted `narmestelederbehov`
code. `LoggingArchitectureTest` checks these
compiled classes, including nested classes. `ApplicationLogging.kt` is the only
native logger factory in that scope. Other application packages and framework
logging are not migrated yet; widen the test scope as they are adopted.

## Adding an event

Keep the definition beside the code that owns the outcome. Use stable, code-owned
event names, operations and error codes. Altinn has one event definition per
operation, with a typed error-code enum selected through `errorCodeFrom`.

Exercise the real logging owner and validate its serialized output with
`RuntimeLogContract.forEvents`. Dynamic codes require an explicit enum-derived
`dynamicErrorCodes` set. This is a shared membership catalog, not a check that a
specific code belongs to a specific event. Scenario tests must also assert the
expected operation, code, level and number of events.

The Altinn and system-access tests load `src/main/resources/logback.xml` with the
JSON branch selected. They validate actual encoded records before filtering by
event identity and retain checks for single log ownership, trace IDs and privacy.
The schema is not a privacy filter: review context fields and causes, and test
sensitive canaries. Keep useful, reviewed diagnostics, including PDL error details
where applicable. Altinn's existing bounded diagnostics and sanitized cause policy
remain local to the app; the shared logger does not scrub or rewrite them.

`FulfillNarmestelederbehovUseCase` logs its typed result directly through two
events defined in `FulfillmentEvents.kt`. A completed operation emits one INFO
event with bounded relation-source and Dialogporten-completion values. An
expected rejection emits one WARN event with a code selected exhaustively from
the result type. Cancellation and unexpected failures propagate without a
business-outcome event. `FulfillmentLoggingContractTest` exercises the use case
with the production JSON encoder, validates every record before filtering, and
checks event counts, severity, trace propagation and sensitive canaries. This
use case is not wired to the production route yet.
