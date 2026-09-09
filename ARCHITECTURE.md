
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

Java 25. The build pins it via `jvmToolchain(25)`, and the foojay resolver in
`settings.gradle.kts` downloads it on a machine that does not have it — so the JDK is a
property of the build, not of whoever is building.

```bash
./gradlew build          # compiles every module, runs tests, enforces the layering
./gradlew test           # domain and types tests only — no container needed
./gradlew :b2b-start:bootRun
```

Needs a Postgres at `DB_URL` (defaults to `jdbc:postgresql://localhost:5432/b2b`).
Flyway owns the schema; Hibernate is set to `validate` and never alters it.

Dependency versions live in `gradle/libs.versions.toml`. The Spring Boot BOM is applied
as a Gradle platform, so no module names a version.

Kotlin is on 2.4.x because earlier versions cap their JVM target below 25 — 2.2 clamps to
24 while javac targets 25, and the build fails on that mismatch rather than quietly
producing inconsistent bytecode. Spring Boot 3.5 is not officially certified above Java 24
but starts and runs on 25 (verified); moving to Boot 4.x is the supported path when
convenient.
