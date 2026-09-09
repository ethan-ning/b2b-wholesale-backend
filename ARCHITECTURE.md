
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
| `b2b-infrastructure` | JPA Data Objects, Spring Data DAOs, converters, port implementations, ACL adapters | `b2b-domain` |
| `b2b-web` | Controllers, request/response adaptation | `b2b-application` |
| `b2b-start` | Spring Boot entry point, configuration, Flyway migrations | `b2b-web`, `b2b-infrastructure` |

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

- `ProductRepository`, `TierPriceRepository`, `CategoryRepository`, `CustomerRepository`
- `StockSyncPort` — the anti-corruption layer for the ERP that owns stock. The domain
  states what it needs (levels per SKU); the adapter translates the vendor's payload, so
  a change to their API stops at the adapter.
- `DealerContext` — who is asking. Declared in the application layer because pricing a
  lookup needs the dealer's tier, but the use case must not know how identity was
  established. The current adapter reads a header; a JWT adapter replaces that one class.

## Business rules encoded

Carried over from the frontend's design work — see the portal repo's
`b2b-wholesale-platform-architecture.md` for the reasoning.

- **One variant axis per SPU.** `Size` for apparel, `Pack Qty` for parts. `Product`
  rejects a multi-SKU product with no axis, and rejects a SKU that does not sit beneath
  its SPU code.
- **Price and MAP are stated per SKU.** No SPU-level row to inherit from. A SKU's price
  is what one of it costs — a garment, or a whole 6-pack — so `Money` and `PackQuantity`
  together give the per-unit figure for display.
- **Quantity-based pricing is deferred but not designed out.** `TierPrice.minQty` exists
  and is pinned to 1; `PricingPolicy` already selects the highest applicable break, and
  a test proves inserting a break row changes the result with no code change.
- **Stock is a read replica.** `StockLevel` carries `lastSyncedAt`, and
  `ProductDataConverter.applyTo` deliberately does not write stock columns, so saving a
  product cannot undo a sync.

## Known gaps

Deliberate, in rough priority order:

1. **Price filtering and sorting use list price**, not the dealer's resolved price.
   `ProductRepositoryImpl` resolves in memory after loading, which is correct for the
   current catalog size but does not scale. The fix is a materialised
   `(sku, tier_id, price)` view that can be joined and sorted in SQL.
2. **No authentication.** `HeaderDealerContext` reads the tier from `X-Dealer-Tier`.
   Replace with a JWT adapter; nothing above the port changes.
3. **Admin write use cases are not built** — only the dealer read slice is. The ports
   (`save`, `replaceFor`, `deleteById`) exist and are implemented.
4. **`StockSyncPort` has no adapter yet.** The scheduled sync job is the next vertical
   slice.
5. **`AttributeCodec` is a placeholder** for the display-attribute bag. Fine for flat
   string values; replace when the column becomes JSONB.

## Running it

Java 21, declared once as `java` in the version catalog and fed to `jvmToolchain()`.

The toolchain governs compilation, `test` and `bootRun`, so the whole development loop
runs on 21 regardless of the machine's default JDK — and a JDK 21 must be installed, or
Gradle will say so. Only a bare `java -jar` on the built artifact uses whatever
`JAVA_HOME` points at; the bytecode is Java 21 either way.

```bash
./gradlew build          # compiles every module, runs tests, enforces the layering
./gradlew test           # domain and types tests only — no container needed
./gradlew :b2b-start:bootRun
```

Needs a Postgres at `DB_URL` (defaults to `jdbc:postgresql://localhost:5432/b2b`).
Flyway owns the schema; Hibernate is set to `validate` and never alters it.

Dependency versions live in `gradle/libs.versions.toml`. The Spring Boot BOM is applied
as a Gradle platform, so no module names a version.

### Where versions live

Two files, split by what the value *is*:

- **`gradle.properties`** — project coordinates (`group`, `version`) and how the build
  runs (parallel, caching, daemon JVM args). Gradle reads `group` and `version` into
  every project, which is why they belong here rather than in `build.gradle.kts`.
- **`gradle/libs.versions.toml`** — what the build depends on: `java`, `kotlin`,
  `springBoot`, and every library and plugin. `java` feeds `jvmToolchain()`, so that one
  line determines the bytecode, and no `build.gradle.kts` contains a version literal.

Dependency versions are deliberately *not* in `gradle.properties`. A project property is
overridable by `-PkotlinVersion=…`, by an `ORG_GRADLE_PROJECT_kotlinVersion` environment
variable, and by a stale `~/.gradle/gradle.properties` — silently, with no warning. That
is the right behaviour for build settings and the wrong behaviour for the compiler
version. Catalog entries cannot be overridden that way, and give type-safe accessors
(`libs.versions.kotlin`) that fail at configuration time on a typo rather than resolving
to an empty string.

Gradle's own version lives in `gradle/wrapper/gradle-wrapper.properties`, because the
wrapper bootstraps before any build script runs. It is not mirrored in the catalog: a
second copy would be decorative, since the wrapper is what actually runs, and a mismatch
between them announces itself immediately.

Two earlier drafts of this are worth not repeating:

- Pinning the daemon's JVM via `gradle-daemon-jvm.properties` and the foojay resolver.
  The daemon's JVM does not affect the bytecode — `jvmToolchain` already guarantees that
  — so it bought only a known JVM for the build *process*, at the cost of a plugin, a
  file of baked download URLs, and a consistency check.
- Mirroring the Gradle version into the catalog and adding a task to keep the two in
  step. That guarded a duplicate that need not have existed.

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
