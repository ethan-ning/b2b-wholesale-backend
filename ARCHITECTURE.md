
# Backend Architecture

Kotlin + Spring Boot backend for the B2B wholesale dealer portal. The layering follows
the DDD application architecture described in the three articles this was built from:
dependencies point inward, and the two innermost layers know nothing about frameworks.

## Modules

Each module may depend only on those above it. The graph is the enforcement — a
controller *cannot* reach a repository implementation, because `b2b-web` does not
depend on `b2b-infrastructure`.

| Module | Contains | Depends on |
|---|---|---|
| `b2b-types` | Domain Primitives: `Money`, `SpuCode`, `SkuCode`, `PackQuantity`, `VariantAxis`, `TierId`, `Quantity` | nothing |
| `b2b-domain` | Entities and aggregates (`Product`, `ProductVariant`, `Customer`), domain services (`PricingPolicy`), and the port interfaces the domain owns | `b2b-types` |
| `b2b-application` | Use-case orchestration (`CatalogQueryService`), DTOs, assemblers | `b2b-domain` |
| `b2b-infrastructure` | JPA Data Objects, Spring Data DAOs, converters, port implementations, ACL adapters, **Flyway migrations** | `b2b-domain` |
| `b2b-web` | Controllers, request/response adaptation | `b2b-application` |
| `b2b-start` | Spring Boot entry point and deployment configuration | `b2b-web`, `b2b-infrastructure` |

`b2b-infrastructure` and `b2b-web` never depend on each other. They meet only in
`b2b-start`, which wires them together.

### The framework-free rule, enforced

`b2b-types` and `b2b-domain` must not depend on Spring, JPA or Hibernate. That is what
makes business rules unit-testable with no container — `PricingPolicyTest` and
`ProductTest` run in milliseconds with no context.

The module graph cannot express "and don't add Spring later", so the root build
registers a `checkFrameworkFree` task on those two modules that fails if a forbidden
group appears on their compile classpath. It is wired into `check`, so `./gradlew build`
catches it.

## The object types, and why there are several

A single class serving as table row, business object and API payload is what couples a
schema change to a UI change. They are kept apart:

| Type | Example | Module | Purpose |
|---|---|---|---|
| **Domain Primitive** | `Money`, `SkuCode` | types | A value that validates itself, so an invalid one cannot exist |
| **Entity / Aggregate** | `Product` | domain | Business rules and invariants, in domain language |
| **DO** | `ProductDO` | infrastructure | One row of one table. No behaviour |
| **DTO** | `ProductDTO` | application | The published contract with the portal |
| **Converter** | `ProductDataConverter` | infrastructure | DO ↔ Entity |
| **Assembler** | `ProductAssembler` | application | Entity → DTO |
| **Repository (port)** | `ProductRepository` | domain | Data access stated in aggregates |
| **Repository (adapter)** | `ProductRepositoryImpl` | infrastructure | Implements the port over Spring Data |

The repository port deals in aggregates, never rows: `ProductDO` is not visible outside
`b2b-infrastructure`.

## Ports and adapters

Anything outside the process is a port the domain declares and infrastructure adapts:

- `ProductRepository`, `TierPriceRepository`, `CategoryRepository`, `CustomerRepository`,
  `CustomerTierRepository`, `ImageRepository`, `ProductImageRepository`
- `SellfoxCatalogPort` and `SellfoxInventoryPort` — the anti-corruption layer for the ERP
  that owns what a product is and how many there are. The domain states what it needs; the
  adapter translates the vendor's payload, so a change to their API stops at the adapter.
- `ImageStore` — the bucket. Cloud Storage when `app.images.bucket` is set, a directory on
  disk when not, chosen in one `@Bean` method so neither depends on a scan-time condition.
- `DealerContext` — who is asking. Declared in the application layer because pricing a
  lookup needs the dealer's tier, but the use case must not know how identity was
  established. `JwtDealerContext` in `b2b-web` is the adapter.

## Where authentication lives

Nothing below the web layer ever sees a token.

| Concern | Module | Why there |
|---|---|---|
| Signing and verification — algorithm, key, claim names | `b2b-infrastructure` (`security/JwtTokens.kt`) | Both halves of one mechanism. Stated once so they cannot drift; moving to asymmetric keys is a change to this file alone |
| Which routes need which authority, CORS | `b2b-web` (`security/WebSecurityConfig.kt`) | The rules describe this module's own endpoints; a controller and the rule protecting it should not be two modules apart |
| Who is asking, for a use case that needs it | `DealerContext` port, application layer | A use case needs the dealer's tier to price a lookup, not the fact that a JWT carried it |

Verification runs in Spring Security's filter chain, ahead of every controller: a request
either arrives authenticated or never reaches one. `b2b-web` injects a `JwtDecoder` and
states no opinion on how it works, which is why its security test stands the whole filter
chain up with a decoder of its own and no infrastructure module at all.

`b2b-start` configures no security. It composes the modules and nothing more.

An earlier arrangement had signing in infrastructure and verification in `b2b-start`, each
independently choosing HS256 and reading the secret. Nothing coupled them — changing the
issuer to RS256 would have left the decoder validating HMAC, with nothing failing to
compile.

## Business rules encoded

Carried over from the frontend's design work — see the portal repo's
`b2b-wholesale-platform-architecture.md` for the reasoning.

- **One variant axis per SPU.** `Size` for apparel, `Pack Qty` for parts. `Product`
  rejects a multi-SKU product with no axis, and rejects a SKU that does not sit beneath
  its SPU code.
- **One tier is the anchor; the rest discount off it.** A SKU's Default price is stated;
  Silver and Gold take `CustomerTier.discount` off it unless that SKU has been given a
  price of its own. `PricingPolicy` is the whole rule in one function, and returns null
  for a SKU with no Default price rather than inventing one — so "unpriced" is a real
  state, and `Product.isSellable` asks that every SKU on sale has a Default price.
- **Supplier cost is not a dealer price.** `product.base_wholesale_price` is derived —
  the cheapest Default price among the SKUs on sale — and read only by search, which
  needs one figure per product to filter and sort on. Nothing is priced from it.
- **MAP is stated per SKU, and prices are per SKU.** No SPU-level row to inherit from. A
  SKU's price is what one of it costs — a garment, or a whole 6-pack — so `Money` and
  `PackQuantity` together give the per-unit figure for display. A discount comes off the
  whole SKU, never the unit price multiplied back, which would round once per unit.
- **Quantity-based pricing is deferred but not designed out.** `TierPrice.minQty` exists
  and is pinned to 1; `PricingPolicy` already selects the highest applicable break, and
  a test proves inserting a break row changes the result with no code change.
- **Stock is a read replica.** `StockLevel` carries `lastSyncedAt`, and
  `ProductDataConverter.applyTo` deliberately does not write stock columns, so saving a
  product cannot undo a sync.

## Dealer portal surface

| Route | Notes |
|---|---|
| `POST /api/auth/login` | BCrypt verify; token carries `scope: DEALER` and the dealer's `tierId` |
| `POST /api/auth/change-password` | ends a forced change and returns a full catalog token |
| `GET /api/products`, `/api/products/{spuCode}`, `/api/categories` | priced for the tier in the token |

**The forced password change is enforced, not requested.** A dealer still on the password
an admin generated receives a token whose only reachable endpoint is
`/api/auth/change-password` — `scope: PASSWORD_CHANGE`, valid 30 minutes. The catalog and
the back office both return 403 to it. The client is told via `mustChangePassword` so it
can route sensibly, but nothing depends on the client honouring that.

**The tier travels in the token**, so pricing a catalog request costs no lookup. The trade
is that a tier change reaches a dealer only at their next login, and a disabled dealer
keeps catalog access until their token expires — acceptable while tiers change rarely and
by deliberate act, but the reason the TTL should not grow.

Login failures are indistinguishable: wrong password, unknown email and disabled account
all return the same 401, so the endpoint cannot be used to enumerate dealers or discover
who has been suspended.

## Admin portal surface

| Route | Notes |
|---|---|
| `POST /api/admin/auth/login` | BCrypt verify, HS256 token with `scope: ADMIN` |
| `GET /api/admin/dashboard` | counting queries only, so it does not slow as the catalog grows |
| `GET/PUT /api/admin/products` | portal-owned fields only. No create and no delete — products arrive from the ERP |
| `POST /api/admin/products/{id}/deactivate` `.../activate` | hides a product from dealers, reversibly |
| `GET/POST/PUT/DELETE /api/admin/categories` | our taxonomy; delete refuses rather than cascades |
| `GET /api/admin/inventory` | read-only; the ERP owns stock |
| `GET/POST/PUT /api/admin/customers`, `POST .../reset-password` | dealer accounts |
| `GET /api/admin/tiers`, `PUT .../tiers/{id}/discount` | a tier's standing rate; changing one reprices every SKU nobody has quoted separately |
| `GET/POST/DELETE /api/admin/images` | the shared image library; a delete is refused while any product shows the image |
| `POST/DELETE /api/admin/products/{id}/images/{imageId}`, `PUT .../images/order`, `PUT .../variants/{id}/main-image` | a product's gallery, capped at nine, and which image stands for each SKU |
| `GET/PUT /api/admin/sellfox/scope`, `POST .../sync` | what is imported, and running an import |
| `GET/POST /api/admin/admins`, `POST .../reset-password` | the back-office roster; only a super admin may write |

Two rules the code enforces structurally rather than by review:

- **`UpdateProductCommand` has no field for name, brand, description, SPU code or variant
  axis.** ERP-owned values cannot be written because there is nowhere to put them, not
  because a check rejects them.
- **Category delete refuses when the node has children or products**, rather than
  cascading. A cascade would unfile products invisibly from the button the admin pressed.
- **A product cannot be shown for one of two reasons, and each says which.** Nothing on
  sale means the supplier withdrew every SKU; no Default price means a SKU has no price at
  any tier. Merged into one sentence, the half that did not apply sent someone looking for
  a setting that did not exist.
- **A product has two states, ACTIVE and INACTIVE, and cannot be deleted.** There is no
  draft — products arrive from the ERP already real — and no separate archived state,
  since it meant the same thing as inactive and two names for one rule invites two
  behaviours. Deactivating is the portal's way to remove something from the catalog: the
  product, its pricing and its history survive, so reactivating restores exactly what was
  there. A delete would be undone by the next sync anyway, having taken the pricing with
  it, so `ProductRepository` has no delete method to call.

### Local sample data

`db/seed/V9xx__*.sql` ships in `b2b-infrastructure` but only applies when
`application-local.yml` adds `classpath:db/seed` to `spring.flyway.locations`. Production
never lists that location, so the seed cannot reach it.

24 products / 55 SKUs / 100 tier-price rows, chosen to exercise the cases a handful of
rows would not: both variant axes and two single-SKU products with no axis at all; all
three statuses, so the admin filters have something to find; every stock state; a product
with no tier prices, which must take its tier's rate; one with no image; one filed
under two categories; and enough rows to page past the default of 10.

Seeds accumulate as new versioned files rather than edits to existing ones — an applied
migration's checksum is fixed and `validate-on-migrate` rejects a change, so editing one
would break every database that already has it.

## Known gaps

Deliberate, in rough priority order:

1. **Price filtering and sorting use the derived reference price**, not the dealer's own.
   `ProductRepositoryImpl` resolves in memory after loading, which is correct for the
   current catalog size but does not scale. The fix is a materialised
   `(sku, tier_id, price)` view that can be joined and sorted in SQL.
2. **The tier discount is stated twice.** The portal computes it as well, so the admin
   grid can follow a Default price as it is typed. `tierPricing.ts` keeps it to one function
   and tests it against the same cases as `Money.lessDiscount`; a materialised price view
   would remove the need.
3. **Search is `ILIKE`, not full-text.** Adequate for a few hundred products; Postgres
   FTS would also give relevance ordering.
4. **`AttributeCodec` is a placeholder** for the display-attribute bag. Fine for flat
   string values; replace when the column becomes JSONB.
5. **Product images have no ordering beyond position**, and no alt-text editing. The
   library is shared, capped at nine per SPU, and each SKU may point at one of them.

## Running it

### Local database

A Postgres container, defined in `compose.yaml`. Colima provides the Docker runtime on
macOS without Docker Desktop:

```bash
colima start --cpu 2 --memory 4     # once per boot
docker compose up -d                # postgres:17 on :5432
docker compose down -v              # discard data, so the next run re-applies every migration
```

Flyway owns the schema and Hibernate runs with `ddl-auto: validate`, so the app refuses to
start if the mappings and the tables disagree — which makes a successful boot a real check
that the two are in step.

### Configuration profiles

**`application.yml` is production.** Running with no profile gives production settings, so
a deployment that forgets to set one fails loudly rather than quietly starting on
developer defaults. Local development is the deviation.

| File | Holds |
|---|---|
| `application.yml` | everything: datasource with no fallbacks, pool sizing, JPA, Flyway, INFO logging, health endpoint only |
| `application-local.yml` | only what differs locally — credentials matching `compose.yaml`, a throwaway signing key, DEBUG logging |

`application-local.yml` overrides four values and inherits the rest, so the two cannot
drift apart.

```bash
./gradlew :b2b-start:bootRun                  # sets the local profile for you
SPRING_PROFILES_ACTIVE=local java -jar ...    # same, by hand
java -jar ...                                 # production: requires the environment
```

The baseline has no fallback values, so an unset variable cannot resolve to something that
happens to be in version control. On its own that fails obscurely — Hikari reports
`'url' must start with "jdbc"` from inside bean creation, never mentioning `DB_URL`.
`RequiredSettingsCheck`, an `EnvironmentPostProcessor`, reports first:

```
Cannot start: required settings are missing.
  - DB_URL  (binds to spring.datasource.url)
  - JWT_SECRET  (binds to security.jwt.secret)
Set them in the environment, or run locally with SPRING_PROFILES_ACTIVE=local.
```

It skips any profile that ships its own defaults (`local`, `test`).

### The application

```bash
./gradlew build                     # every module, tests, and the layering guards
./gradlew :b2b-start:bootRun        # needs the database above
```

Seeded by `V2` for local development: `admin@example.com` / `admin123`, and the Gold and
Silver tiers the pricing model is written against. That password is in version control and
must be changed anywhere beyond a laptop.

Java 21, declared once as `java.version` in `gradle.properties` and fed to `jvmToolchain()`.

The toolchain governs compilation, `test` and `bootRun`, so the whole development loop
runs on 21 regardless of the machine's default JDK — and a JDK 21 must be installed, or
Gradle will say so. Only a bare `java -jar` on the built artifact uses whatever
`JAVA_HOME` points at; the bytecode is Java 21 either way.

```bash
./gradlew build          # compiles every module, runs tests, enforces the layering
./gradlew test           # domain and types tests only — no container needed
./gradlew :b2b-start:bootRun
```

Versions live in `gradle.properties`; the Spring Boot BOM is applied as a Gradle
platform, so no module names a library version.

### Where versions live

Everything is in **`gradle.properties`** — project coordinates, `java.version`,
`kotlin.version`, `spring-boot.version`, and how the build runs. No `build.gradle.kts`
contains a version literal.

The dotted names cost one small thing: Gradle's `by project` / `by settings` delegates
require the property name to match the variable name, so these are read explicitly with
`providers.gradleProperty("kotlin.version").get()`. That `.get()` fails the build if the
property is missing, rather than falling back to a default:

```
Cannot query the value of Gradle property 'kotlin.version' because it has no value available.
```

`java.version` as a *project* property does not collide with the JVM system property of
the same name — separate namespaces, verified.

Two mechanics make that work:

- **Plugin versions** are resolved in `settings.gradle.kts`, inside `pluginManagement`.
  A build script's `plugins {}` block cannot read project properties, but `by settings`
  can, so each plugin is versioned once there and applied without a version everywhere
  else.
- **Library versions** mostly do not need declaring. The Spring Boot BOM is applied as a
  Gradle platform in every module, so `spring-boot-starter-web`, `jackson-module-kotlin`,
  `flyway-core`, `postgresql` and the rest are named without versions and stay mutually
  consistent.

Gradle's own version lives in `gradle/wrapper/gradle-wrapper.properties`, because the
wrapper bootstraps before any build script runs.

One consequence to know about: project properties are overridable by `-PkotlinVersion=…`,
by an `ORG_GRADLE_PROJECT_kotlinVersion` environment variable, and by
`~/.gradle/gradle.properties` — silently. Convenient for a one-off experiment; worth
remembering if a build ever resolves a version nobody declared.

Two earlier drafts are worth not repeating:

- Pinning the daemon's JVM via `gradle-daemon-jvm.properties` and the foojay resolver.
  The daemon's JVM does not affect the bytecode — `jvmToolchain` already guarantees that.
- Mirroring the Gradle version into a second file and adding a task to keep the two in
  step, which guarded a duplicate that need not have existed.

### Why these versions

**Java 21** — an LTS inside Spring Boot 3.5's certified support matrix. Boot 3.5 does run
on 25, but 25 is outside what it is tested against, and there is nothing in this codebase
that needs a newer JVM.

**Spring Boot 3.5.0** with **Kotlin 2.2.21**. Boot's BOM pins `kotlin.version` to 1.9.25,
but that constraint loses to the Kotlin plugin's own dependency during conflict
resolution (`1.9.25 -> 2.2.21 (c)`), so the compiler and stdlib stay aligned and the BOM
does not dictate the Kotlin version. The choice therefore comes down to Gradle:

- 2.1.x predates official Gradle 9 support. It builds here, but "happens to work" is a
  poor foundation.
- **2.2.x** is the line that added Gradle 9 support, is several patch releases in, and is
  contemporaneous with Boot 3.5 — the ecosystem around it (`jackson-module-kotlin`,
  coroutines) was built and tested against this era.
- 2.3.x and 2.4.x also build cleanly with no warnings. Moving up is a one-line change in
  the version catalog; there is simply no current reason to pair a 2026 compiler with a
  2025 framework.

All four were verified to build, pass tests and emit no compatibility warnings before
settling on 2.2.21.
