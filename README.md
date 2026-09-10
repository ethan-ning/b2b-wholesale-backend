# B2B Wholesale — backend

Dealer catalogue and admin API for a wholesale portal. Kotlin, Spring Boot 3, Postgres,
Gradle. Product data comes from the Sellfox ERP; pricing, categories and dealer visibility
belong to the portal.

For how the modules relate and why, see [ARCHITECTURE.md](ARCHITECTURE.md). This file is
about getting it running.

## What you need

- **JDK 21.** Gradle provisions a matching toolchain, so any local JDK will do to start
  the wrapper.
- **Docker**, for the local Postgres. Nothing else is containerised.

## Run it

```bash
docker compose up -d          # Postgres on 5432, waits until it answers pg_isready
./gradlew :b2b-start:bootRun  # http://localhost:8080
```

`bootRun` sets `spring.profiles.active=local` for you. Flyway creates the schema on first
boot and, under the local profile only, loads a sample catalogue on top of it.

Check it came up:

```bash
curl -s localhost:8080/actuator/health     # {"status":"UP"}
```

Sign in with the seeded accounts — these exist **only** under the local profile:

| Account | Password | Role |
|---|---|---|
| `admin@example.com` | `admin123` | Admin portal, SUPER_ADMIN |
| `dealer1@example.com` | `dealer123` | Dealer, Gold tier |
| `dealer2@example.com` | `dealer123` | Dealer, Silver tier |

```bash
curl -s -X POST localhost:8080/api/admin/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"admin123"}'
```

The response carries a JWT; send it as `Authorization: Bearer <token>` on everything else.

### Stopping and resetting

```bash
docker compose down       # stop, keep the data
docker compose down -v    # discard the volume, so the next boot re-runs every migration
```

Reach for `down -v` after editing a migration. Flyway checksums applied migrations and
refuses to start when one has changed underneath it, which is the intended behaviour —
a migration edited after being applied elsewhere is a deployment accident.

## Test it

```bash
./gradlew test              # 160 unit tests. No Docker, no network.
./gradlew integrationTest   # 16 tests against a real Postgres. Needs Docker.
./gradlew build             # both, plus the layering guards below
./gradlew jacocoTestReport  # coverage, per module, under build/reports/jacoco
```

The two are separate tasks on purpose. Unit tests must run anywhere — on a plane, in a
pre-commit hook — and the moment a Postgres-backed test shares the `test` task nobody can
tell which kind failed.

### Unit tests

The domain and types modules are pure Kotlin; the application tests drive use cases
against hand-written in-memory fakes rather than mocks, so they exercise behaviour rather
than call sequences; the web tests are `@WebMvcTest` slices. Nothing touches Postgres or
Sellfox.

| Module | Tests | What they cover |
|---|---|---|
| `b2b-types` | 14 | SKU and SPU code shapes, money arithmetic, email |
| `b2b-domain` | 57 | Product invariants, tier pricing, SPU grouping, sync run states |
| `b2b-application` | 79 | The pricing guard, dealer auth, the whole sync, categories, customers |
| `b2b-web` | 10 | That admin endpoints reject dealer tokens and anonymous callers |

Two to read first. `SpuGroupingTest` is the most intricate logic here and doubles as its
specification. `ProductAdminServiceTest` covers the rule that costs money if it breaks: a
product no dealer could buy from must not be visible, enforced on both routes that can set
visibility.

### Integration tests

`b2b-infrastructure/src/integrationTest` runs against a real Postgres in a container, with
the real migration and the real mappings — `ddl-auto: validate` means the context does not
even start unless every entity matches the table Flyway created.

They exist for the things a fake cannot be wrong about in the same way: whether a cascade
fires, whether a flush ordering behaves as assumed, whether a collection Hibernate is
holding still matches the database. Two live bugs turned up when they were first written,
both of which only appear once the sync runs inside a single transaction.

Docker discovery is automatic. Testcontainers looks for `/var/run/docker.sock`, which is
not where Colima, Rancher or a rootless daemon put it, so the task asks the active Docker
context and passes the answer through. Nothing to export.

### The layering guards

`./gradlew build` runs two checks that fail the build rather than merely warning:

- **`checkLayering`** — each module may depend only on those above it in
  `settings.gradle.kts`. Adding a forbidden project dependency fails here, not in review.
- **`checkFrameworkFree`** — `b2b-types` and `b2b-domain` must not pull in Spring,
  Jackson, JPA or anything similar. The domain stays plain Kotlin.

Run them alone with `./gradlew checkLayering checkFrameworkFree`.

## Talking to Sellfox

The sync is off by default and does nothing without credentials. Set them and restart:

```bash
export SELLFOX_APP_ID=...
export SELLFOX_APP_SECRET=...
./gradlew :b2b-start:bootRun
```

Without them the application still starts; only a sync run fails, and it fails into the
run history where you can read the reason. There is no Sellfox sandbox — a sync tested
against a stub is a sync that has not been tested — so local development uses the real
API, which means two things worth knowing:

- **The account is IP-allowlisted.** Code `40005` (`访问的客户端ip不在白名单列表`) means your
  egress address changed, not that anything is broken. Add it in Sellfox.
- **It is rate limited.** Code `40019` arrives as HTTP **400**, not 429, so the client
  reads the business code rather than the status line. Paging is deliberately paced.

Trigger a run by hand:

```bash
TOKEN=...   # from the admin login above
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  'localhost:8080/api/admin/sellfox/runs?mode=inventory'   # stock only, seconds
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  'localhost:8080/api/admin/sellfox/runs'                  # full catalogue, ~2 minutes
curl -s -H "Authorization: Bearer $TOKEN" \
  'localhost:8080/api/admin/sellfox/runs?limit=5'          # what happened
```

A run returns immediately with its record in `RUNNING`; poll `/runs` for the outcome. Both
cron schedules are off locally, so nothing rewrites your data while you are looking at it.

## Configuration

`application.yml` is production and has **no fallbacks** — an unset variable must not
resolve to something that happens to be in version control. `RequiredSettingsCheck` names
the missing one before any bean is built. `application-local.yml` overrides only what
genuinely differs.

| Variable | Local default | Notes |
|---|---|---|
| `DB_URL` `DB_USER` `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/b2b`, `b2b`, `b2b` | Required in production |
| `JWT_SECRET` | a throwaway string | Required in production; 32 bytes or more |
| `JWT_TTL_MINUTES` | 480 | |
| `SELLFOX_APP_ID` `SELLFOX_APP_SECRET` | empty | Sync fails without them; the app still runs |
| `SELLFOX_SCHEDULE_ENABLED` | `false` | Cron off locally and by default in production |
| `SELLFOX_RUN_STALE_AFTER` | `PT15M` | How old a RUNNING row must be before startup declares it dead |
| `SERVER_PORT` | 8080 | |

## Deploying

Production is the default profile — running with no profile gives you production settings,
so a deployment that forgets to set one fails loudly rather than quietly starting on
developer defaults.

```bash
./gradlew :b2b-start:bootJar    # b2b-start/build/libs/
./build-image.sh                # the same jar, with the portal, as one container image
```

The portal is packaged into the same image and served from the same origin, so there is
one artifact to deploy and nothing is cross-origin. See [DEPLOY.md](DEPLOY.md) for Cloud
Run — including the two flags the background sync depends on.

Two things the local seed does that production must do deliberately:

- **The first admin.** The seed's bootstrap account lives in `db/seed`, which production
  never puts on the Flyway path — a migration that seeds a known password would run
  everywhere, and this repository is public. Insert your first admin explicitly at deploy
  time, with a BCrypt hash you generated.
- **The cron.** Set `SELLFOX_SCHEDULE_ENABLED=true` where you want it. It is safe to leave
  on across several instances — the database allows one RUNNING row at a time, so the
  others are simply turned away — but a scaled-to-zero platform will not fire it at all,
  and background work needs CPU allocated outside a request. On Cloud Run that means
  `--min-instances=1 --no-cpu-throttling`, or moving the trigger to Cloud Scheduler
  calling `POST /api/admin/sellfox/runs`.

## API shape

Dealer endpoints sit under `/api`, admin under `/api/admin`, and the two hold separate
sessions with separate tokens. A dealer token on an admin route is rejected — that is what
the `b2b-web` tests exist to prove.

| | |
|---|---|
| `POST /api/auth/login`, `/api/auth/change-password` | dealer session |
| `GET /api/products`, `/api/products/{spuCode}`, `/api/categories` | dealer catalogue |
| `POST /api/admin/auth/login` | admin session |
| `GET/PUT /api/admin/products`, `.../{id}`, `.../{id}/activate` | catalogue admin |
| `GET/POST/PUT/DELETE /api/admin/categories` | taxonomy |
| `GET/POST/PUT /api/admin/customers`, `.../{id}/reset-password` | dealers |
| `GET /api/admin/inventory`, `/api/admin/dashboard` | read-only views |
| `GET/PUT /api/admin/sellfox/scope`, `GET/POST /api/admin/sellfox/runs` | the sync |

Only `/actuator/health` is exposed; nothing else from Actuator.
