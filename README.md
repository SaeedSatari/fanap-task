# network — Network Device Management and Connectivity (Ping) Monitoring

Implementation of the Senior Java Developer take-home assignment, built with **Java 17 + Spring Boot 3.3 + Spring Data JPA + H2 (file)**.

## Running

### With Docker Compose (recommended)

No local Java or Maven installation is needed.

```bash
docker compose up -d --build                      # build and run; the database lives on a volume
docker compose --profile test run --rm smoke      # smoke test (starts once the application is healthy)
docker compose restart network                    # demonstrate recovery after a restart
docker compose logs -f network                    # application logs
docker compose down                               # stop (add -v to also delete the data)
```

- The `smoke` service reuses the application image (it already contains `bash` and `curl`), so nothing is downloaded.
- The first build is slow because Maven downloads its dependencies. Maven runs with a cache mount, so later builds are fast.
- If port 8080 is taken, change `"8080:8080"` in `docker-compose.yml` (for example to `"9090:8080"`).

### Without Docker

```bash
mvn test              # all JUnit tests
mvn spring-boot:run   # run on http://localhost:8080
```

### Testing the API

- **Swagger UI:** http://localhost:8080/swagger-ui.html (interactive, with "Try it out")
- `./smoke-test.sh [base-url]` — automated smoke test (bash + curl only, about 15 seconds). If the server is not reachable it stops with a clear message and exit code 2. At the end it prints the number of passed and failed checks.
- `requests.http` — sample requests (IntelliJ / VS Code REST Client).

Data is stored in `./data/network.mv.db` (or in the Docker volume) so that it survives a restart. This is required to demonstrate recovery.

## API documentation (Swagger / OpenAPI)

The public API is documented with [springdoc-openapi](https://springdoc.org/).

| URL | Content |
|---|---|
| `/swagger-ui.html` | Interactive Swagger UI |
| `/v3/api-docs` | Raw OpenAPI 3 document (JSON) |

- Endpoints are grouped into three tags: **Devices**, **Ping** and **Monitor**.
- Every operation lists its possible responses, including the error codes and the shared error body (`ApiError`).
- Only `/api/**` is documented. The internal service endpoints (`/internal/**`) are deliberately hidden.

Setup:

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
  <version>2.6.0</version> <!-- the 2.6.x line matches Spring Boot 3.3.x -->
</dependency>
```

```yaml
# application.yml
springdoc:
  paths-to-match: /api/**
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method
    tags-sorter: alpha
```

API metadata (title, version, description) is defined in `OpenApiConfig`, and the descriptions and examples come from `@Tag`, `@Operation`, `@ApiResponse`, `@Parameter` and `@Schema` annotations on the controllers and request records.

## Architecture

Choice: a **modular monolith** with two logical "services" that talk to each other only over HTTP using `RestTemplate`.

```
Client ─► /api/v1/**  (PingApiController, MonitorApiController, DeviceController)
              │  RestTemplate (timeouts + error mapping)
              ├─► /internal/ping/**     Ping service    (request intake, async execution, history)
              └─► /internal/monitor/**  Monitor service (availability and latency statistics)
                         │
                   Domain: Device, PingOperation  ─►  H2/JPA
                         │
                   Worker pool ─► PingProbe (Mock)
```

**Why a monolith?** For a domain this small, deployment and transactions are simpler. The service boundaries are still real: an HTTP contract, a separate client and separate timeouts for each service. To split either one out, set `network.clients.ping.base-url` / `network.clients.monitor.base-url` to another address.

**What to split first when scaling?** The ping worker, because it needs to scale independently (many slow, I/O-bound probes). When splitting it out:
- `/internal/**` must only be reachable inside the internal network (or protected with mTLS).
- The shared database should be replaced by a message queue or an outbox for handing operations to workers.

## Data model

- `devices`: id, name, ipAddress (unique), type (`MODEM|RADIO`), monitoringEnabled, lastStatus (`UP|DOWN|UNKNOWN`), lastCheckedAt
- `ping_operations`: id (UUID), deviceId, idempotencyKey, status, attempts/maxAttempts, nextAttemptAt, leaseExpiresAt, reachable, responseTimeMs, errorMessage, createdAt/startedAt/finishedAt  
  Unique constraint on `(device_id, idempotency_key)`.

## Operation lifecycle

```
PENDING ──claim──► RUNNING ──success─────────────────────────► SUCCEEDED (reachable true/false)
   ▲                  │ error/timeout, attempts left
   └──── backoff ◄────┤
                      ├─ error, no attempts left ────────────► FAILED
                      ├─ timeout, no attempts left ──────────► TIMED_OUT
                      └─ lease expired / restart ────────────► PENDING (attempts left) or FAILED
```

"The device is down" is a **successful operation** with `reachable=false`. `FAILED` / `TIMED_OUT` mean the probe itself could not produce a conclusive result. In that case the device status becomes `UNKNOWN` and the operation is not counted in availability.

## Correctness and concurrency

| Concern | Solution |
|---|---|
| Duplicate request | `Idempotency-Key` header: the original operation is returned (200 + `Idempotent-Replayed: true`) and no new ping is executed. A database unique constraint is the second line of defense. Automatic monitoring uses a time-bucket key. |
| Concurrent requests for one device | On submission the device row is locked with `SELECT … FOR UPDATE`. If a `PENDING`/`RUNNING` operation exists, the response is `409 ACTIVE_OPERATION_EXISTS`. Deleting the device in that state is also a 409. |
| Two workers, one operation | `claim` is a conditional UPDATE (`PENDING→RUNNING`); only one caller wins. |
| "Zombie" worker | Every state change is guarded by the attempt number (fencing); the result of a stale attempt is ignored. |
| Old result overwriting a newer status | `lastStatus` is updated only if `lastCheckedAt` is newer than or equal to the stored value. |
| Timeout | The probe runs in a separate thread and is interrupted with `Future.get(timeout)` + `cancel(true)`. |
| Retry | Up to `max-attempts` attempts with exponential backoff (1s, 2s, 4s … capped). |
| Restart / crash | Every `RUNNING` operation holds a lease. On startup all leftover `RUNNING` operations are recovered, and while running any operation with an expired lease is too: it goes back to `PENDING`, or to `FAILED` if no attempts remain. A ping is idempotent, so re-running it is safe. A sweeper also dispatches due `PENDING` operations every second, so nothing stays in an undefined state. |
| Full queue | The operation stays `PENDING` in the database and is picked up later by the sweeper. |

> Assumption: on startup, every `RUNNING` operation belongs to the previous process (single instance). For multiple instances set `network.recovery.requeue-running-on-startup=false` so that only the lease decides.

## RestTemplate behavior (timeout, HTTP errors, invalid responses)

All of it is defined in `DownstreamCaller`:

| Situation | Result for the API client | Retry |
|---|---|---|
| Timeout (connect/read) | `504 DOWNSTREAM_TIMEOUT` | GET only (default: 1 extra attempt) |
| Connection not possible | `503 DOWNSTREAM_UNAVAILABLE` | GET only |
| HTTP 5xx | `502 DOWNSTREAM_ERROR` | GET only |
| HTTP 4xx | Same status and code as the downstream service (for example 404 `DEVICE_NOT_FOUND`, 409) | No |
| Empty body, broken JSON, wrong content type, missing required fields | `502 INVALID_DOWNSTREAM_RESPONSE` | No |

A POST is never retried automatically; the client can safely resend it with the same `Idempotency-Key`.
All errors use one shape: `{timestamp, status, code, message, details?}`.

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/devices` | Create (201) |
| GET | `/api/v1/devices?page&size` | Paginated list |
| GET/PUT/DELETE | `/api/v1/devices/{id}` | Read / update / delete |
| PUT | `/api/v1/devices/{id}/monitoring` | `{ "enabled": true/false }` |
| POST | `/api/v1/devices/{id}/pings` | Start an async ping: 202 (new) / 200 (replayed) |
| GET | `/api/v1/pings/{operationId}` | Operation status and result |
| GET | `/api/v1/devices/{id}/pings?limit` | History |
| GET | `/api/v1/devices/{id}/monitor?window` | Statistics: availability, average/min/max response time, recent operations |

If `monitoringEnabled=false`, manual pings are rejected too (`422 MONITORING_DISABLED`) and no automatic pings are scheduled.
For devices with monitoring enabled, a ping is scheduled automatically every `network.monitoring.interval-ms` (default: 30 seconds).

## Simulated ping

`MockPingProbe` behaves deterministically for the documentation IP ranges (RFC 5737), so every failure path can be demonstrated on demand:

| IP | Behavior |
|---|---|
| `192.0.2.x` | Device is down (`SUCCEEDED`, `reachable=false`) |
| `198.51.100.x` | The probe never answers → timeout → `TIMED_OUT` |
| `203.0.113.x` | The probe errors → retries → `FAILED` |
| anything else | Random latency; the chance of being down or of a transient error is configurable (`network.mock.*`) |

## Tests

- `DeviceRequestValidationTest`: validation of IP, name and type
- `DeviceServiceTest`: duplicate IP, deleting while an operation is active, updates
- `PingWorkerTest`: success / error / retry with backoff / timeout / last attempt / failed claim
- `MonitorServiceTest`: statistics calculation
- `PingServiceClientTest` and `MonitorServiceClientTest` (`MockRestServiceServer`): timeout, 5xx, 4xx, connection refused, broken JSON, empty body, missing fields, retry for GET only
- `PingOperationStoreIntegrationTest`: state transitions, result persistence, concurrent claim, stale attempt, **recovery after a restart**
- `PingSubmissionConcurrencyTest`: 8 concurrent requests → only one operation; same key → one operation; repeating a key after completion
- `PingApiEndToEndTest`: the whole flow over real HTTP
- `smoke-test.sh`: smoke test against a running application (CRUD, idempotency, concurrent 409, timeout, retry, 8 parallel requests)

## Limitations and next steps

- IPv6 is not supported (only IPv4 is validated).
- `/internal/**` has no authentication; in a real deployment it must only be reachable inside the network or protected with mTLS.
- For production: PostgreSQL + Flyway instead of `ddl-auto=update`, and Micrometer metrics for queue depth and latency.