# Running the backend tests

This guide explains how to run the tests of the API (`api/`): unit tests, integration tests against a real
PostgreSQL, a single class, and a different class order. It also describes the shared test support used by the
integration tests.

## Prerequisites

- JDK 17.
- Docker running (Docker Desktop on Windows and macOS, Docker Engine on Linux). The integration tests start their own
  PostgreSQL 16 container with [Testcontainers](https://java.testcontainers.org/); they never use the database of the
  Docker Compose stack, and they stop their containers at the end of the run.
- Network access on the first run, to download the Maven dependencies and the `postgres:16-alpine` and
  `testcontainers/ryuk` images. Later runs can add `-o` (offline).

Testcontainers 1.21.4 or later is required with Docker Engine 29: older versions talk to Docker with API version 1.32,
which Docker Engine 29 rejects (see [Troubleshooting](#troubleshooting)).

## Commands

Run them from `api/`. On Windows use `mvnw.cmd` (PowerShell shown); elsewhere use `mvn`.

| What | Command | Measured time* |
|---|---|---|
| Everything (unit + integration) | `.\mvnw.cmd -B test` | 4 min to 4 min 20 s (1276 tests) |
| Unit tests only (no Docker needed) | `.\mvnw.cmd -B test "-Dtest=!*IntegrationTest*" "-Dsurefire.failIfNoSpecifiedTests=false"` | 25 s (999 tests) |
| Integration tests only | `.\mvnw.cmd -B test "-Dtest=*IntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | 3 min 55 s to 4 min 15 s (277 tests) |
| One class | `.\mvnw.cmd -B test "-Dtest=TestSupportIntegrationTest"` | about 1 min |
| One nested group | `.\mvnw.cmd -B test '-Dtest=WorkOrderIntegrationTest$CreateTests'` | about 1 min |
| One method | `.\mvnw.cmd -B test "-Dtest=UserIntegrationTest#count_returnsCorrectCount"` | about 1 min |
| Integration tests, one class after another, in reverse order | add `"-Djunit.parallel=false" "-Dsurefire.runOrder=reversealphabetical"` to the integration command | 3 min 50 s |

\* Windows 11, Docker Desktop with Docker Engine 29.7.2, offline (`-o`), 20/11/2026. Each time includes compilation
and the start of the Spring contexts.

Notes:

- The unit filter ends with `*`: `!*IntegrationTest` alone does not exclude the nested classes of the integration
  tests (`ExportIntegrationTest$RequestExport`, ...), which then need Docker.
- The tests run in UTC (`-Duser.timezone=UTC` in `api/pom.xml`), whatever the time zone of the machine.

- Test classes run in parallel (4 threads, `api/pom.xml`). The integration classes share the lock
  `cmms-test-db` (`AbstractTestContainer`), so they run one at a time, in an order chosen by JUnit.
  `-Djunit.parallel=false` runs every class one after another, so that `-Dsurefire.runOrder` decides the order and a
  failure that depends on the order can be reproduced.
- Results per class are in `api/target/surefire-reports/*.xml`. The console lines of parallel classes interleave.
- Do not build the project from an IDE or a language server while Maven runs: both write `api/target`.

## How the integration tests work

- A test class is an integration test when its name ends in `IntegrationTest`. It extends
  `com.grash.integration.AbstractIntegrationTest`.
- One PostgreSQL 16 container is started per Maven run and shared by every integration class. Liquibase applies all
  the changesets when the first Spring context starts, as in production; the application then creates its reference
  data (default roles, plans).
- Add `@AutoConfigureMockMvc` to call the API through the full chain (JWT filter, controller, service, database).

### Isolation rules

Any class must pass alone, in any order, and after any other class. Therefore a test:

1. Creates its own data, with unique names and emails (`TestOrganizations` does this).
2. Never asserts on global state ("there are 2 users"). Scope queries to its own organizations, or compare with a
   count taken at the start of the test.
3. Never deletes or changes shared reference data: default roles, subscription plans, currencies. If it changes one,
   it restores it.
4. Either is `@Transactional` (rolled back at the end), or deletes what it committed, and only that.

## Test support

All in `api/src/test/java/com/grash/integration/support/`. `TestSupportIntegrationTest` shows each one in use.

### Common starting data: `TestOrganizations`

Creates the starting data of the acceptance criteria: organization **O1** with its owner **U0**, administrator **A**
and technician **T**, and organization **O2** with its own administrator. The organizations are created as signup
creates them (`BUSINESS` plan, default roles):

| User | Role | Owns the company |
|---|---|---|
| U0 (`o1().owner()`) | default ADMIN | yes |
| A (`o1().admin()`) | default ADMIN: view of `SETTINGS`, `editOther` and `deleteOther` on every entity | no |
| T (`o1().technician()`) | default TECHNICIAN: no `editOther`, no `deleteOther` | no |
| O2 administrator (`o2().admin()`) | default ADMIN | yes |

```java
@AutoConfigureMockMvc
class MyIntegrationTest extends AbstractIntegrationTest {
    private final TestOrganizations organizations;
    private final TestApi api;

    @Autowired
    MyIntegrationTest(TestOrganizations organizations, MockMvc mockMvc, ObjectMapper objectMapper) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
    }

    @Test
    void example() {
        CommonData data = organizations.create();
        String token = api.login(data.o1().admin());
        Response response = api.call(delete("/assets/" + id), token);
        assertEquals(409, response.status());
    }
}
```

`organizations.delete(data)` removes the organizations when the test commits data. Records that T must not have
created should be created by another user.

### Doubles of external services

`AbstractIntegrationTest` gives every test new doubles. They record each call and can simulate a failure:

| Field | Replaces | Call names |
|---|---|---|
| `mailDouble` | the mail service returned by `MailServiceFactory` | `MailService` methods (`sendSimpleMessage`, ...) |
| `storageDouble` | the storage service returned by `StorageServiceFactory`; keeps the objects in memory | `upload`, `download`, `generateSignedUrl`, `delete` |
| `webhookDouble` | the HTTP client of `WebhookDispatchService` (the real dispatch runs; nothing leaves the JVM) | the event, for example `NEW_REQUEST`; arguments `[url, body]` |

```java
assertTrue(mailDouble.calls().isEmpty());                                // nothing was sent
storageDouble.failOn("upload", new IllegalStateException("storage down")); // the next uploads fail
webhookDouble.awaitCalls("PART_DELETE", 1, Duration.ofSeconds(10));       // webhooks are asynchronous
```

`CallRecorder.ANY` arms a failure for every call. `mailService` is still a Mockito mock (backed by `mailDouble`), so
`verify(mailService)...` keeps working. A webhook endpoint must use `WebhookDouble.ENDPOINT_URL`, which passes the
URL validation without DNS. When `StorageService` gains a method (for example, a delete), implement it in
`StorageDouble` with `record(...)`.

### Concurrency: `Concurrently`

Runs N tasks at the same time, each on its own thread, from a start gate that opens when all of them are ready.
Returns one `Outcome` (value or exception) per task, in task order; a failing task does not stop the others.

```java
List<Outcome<Response>> outcomes = Concurrently.run(8, i -> () -> api.call(post("/requests")..., token));
assertEquals(1, outcomes.stream().filter(o -> o.succeeded() && o.value().is2xx()).count());
```

### Injected failure: `FaultInjector`

Makes a database operation fail at a chosen point, with temporary PostgreSQL triggers. Production code is not
changed.

| Method | Effect |
|---|---|
| `failOn(table, op)` | the first `op` (`INSERT`, `UPDATE`, `DELETE`) on a row of `table` fails |
| `failOn(table, op, n)` | the first `n` rows pass, the next one fails (a failure halfway) |
| `failAtCommit(table, op)` | the statements pass and the transaction fails at commit |
| `runBefore(table, op, sql)` | runs `sql` once, just before the first `op`, in the same transaction (a concurrent change between a check and the operation) |

```java
FaultInjector faults = new FaultInjector(jdbcTemplate);
try (Fault fault = faults.failOn("part_quantity", Operation.INSERT)) {
    Response response = api.call(..., token);
    assertEquals(1, fault.hits());      // the fault point was crossed
}
```

Call `faults.removeAll()` in an `@AfterEach`. The error message starts with `P-00 injected fault`.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Could not find a valid Docker environment`, with `BadRequestException (Status 400` for each strategy | Testcontainers older than 1.21 uses Docker API 1.32; Docker Engine 29 accepts 1.40 or later. Use the version in `api/pom.xml` (`testcontainers.version`) and do not override it |
| `Could not find a valid Docker environment` without `Status 400` | Docker is not running, or `DOCKER_HOST` / `~/.testcontainers.properties` points to another engine. Check `docker info` |
| `error reading ...MapperImpl.java` during compilation | Another process (IDE, language server) is writing `api/target`. Stop it and run again |
| A class passes alone and fails in the suite | It depends on data left or deleted by another class. Reproduce with `-Djunit.parallel=false -Dsurefire.runOrder=reversealphabetical` and apply the isolation rules |
