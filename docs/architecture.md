# OpsVision MCP Server — Architecture

> A read-only Model Context Protocol (MCP) server that exposes warehouse-picking
> investigation tools for the SCPS Marunda site, designed for AI agents to
> reconstruct timelines, evaluate operational rules, and pull structured
> evidence — without writing a line back to production data.

**Audience.** Engineers, ops, and stakeholders evaluating how the server fits
into the SCPS investigation tooling. Suitable for slide adaptation.

---

## 1. Executive summary

| Aspect | Value |
|---|---|
| **Purpose** | Surface SCPS warehouse picking-flow facts to AI agents over MCP |
| **Tech stack** | Java 21 · Spring Boot 4.0.6 · Spring AI 2.0.0-M5 · MCP over Streamable HTTP (negotiates 2025-03-26 or 2025-11-25) · JdbcClient · HikariCP 7 · PostgreSQL 13 |
| **Scope** | 19 read-only tools, 3 PostgreSQL datasources, 14 repositories, 18 evidence DTO records + 9 lifecycle enums + `SignalKind` enum |
| **Site** | Marunda (`MAR-0000000001` distribution + `MAN-0000000002` non-distribution) |
| **Read-only** | Triple-enforced (Hikari · session SQL · `@Transactional(readOnly=true)`) + integration test |
| **Philosophy** | **Evidence, not verdicts** — tools return structured facts; the agent draws conclusions |

The server is a thin, bounded façade over three production-shape PostgreSQL
databases. It does no business writes, holds no state, and never passes
operator opinions to the agent.

---

## 2. System context

```
                      ┌─────────────────────────────┐
                      │  Operations / Support Agent │
                      │  (LLM running on harness)   │
                      └──────────────┬──────────────┘
                                     │  MCP / JSON-RPC 2.0
                                     │  over Streamable HTTP
                                     ▼
   ┌───────────────────────────────────────────────────────────────┐
   │             opsvision-mcp-server  (this repo)                 │
   │                                                               │
   │   ┌─────────────┐   ┌─────────────┐   ┌──────────────────┐    │
   │   │ Tool layer  │ → │ Repo layer  │ → │ JdbcClient × 3   │    │
   │   │ (19 @Tool)  │   │ (14 repos)  │   │ (Hikari pools)   │    │
   │   └─────────────┘   └─────────────┘   └────────┬─────────┘    │
   └─────────────────────────────────────────────────┼─────────────┘
                                     ┌───────────────┼───────────────┐
                                     ▼               ▼               ▼
                          ┌─────────────────┐ ┌──────────────┐ ┌──────────────┐
                          │ stockholm       │ │ warehouse-   │ │ warehouse-   │
                          │ marunda_restore │ │ inventory_   │ │ stock_       │
                          │ (DB 413)        │ │ marunda      │ │ movement_    │
                          │                 │ │ (DB 417)     │ │ marunda      │
                          │ Pick package /  │ │ Bin-level    │ │ (DB 416)     │
                          │ Sales orders /  │ │ stock /      │ │ WCS picking  │
                          │ Picker / Pick   │ │ stock_history│ │ task ledger  │
                          │ list / Packing  │ │              │ │              │
                          └─────────────────┘ └──────────────┘ └──────────────┘
                                       (all three are read-replicas in QA2)
```

**Key boundaries**

- The server **does not** call upstream business services (Stockholm WMS, Movement, Inventory). It reads the same Postgres schemas those services persist into.
- The server **never** issues `INSERT/UPDATE/DELETE`; all three layers of read-only enforcement reject DML.
- No sessions, caches with TTL, or in-flight state — every call is stateless except for one tiny in-memory map (the `warehouse_defect_map` lookup, ~tens of rows, reloaded on cold restart).

---

## 3. High-level architecture

The server is a four-layer Spring Boot application:

```
   ┌─────────────────────────────────────────────────────────────────┐
   │  Transport: spring-ai-starter-mcp-server-webmvc                 │
   │  (MCP 2025-03-26 over Streamable HTTP, listens on :8081/mcp)    │
   └─────────────────────────────────────────────────────────────────┘
                                  ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  Tool layer  (19 @Tool methods registered in McpToolsConfig)    │
   │                                                                 │
   │   Composes evidence packs from one or more repositories, runs   │
   │   pure-Java derivations (lifecycle stages, boolean signals,     │
   │   queue-rank, freshness histograms). Never touches SQL directly.│
   └─────────────────────────────────────────────────────────────────┘
                                  ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  Repository layer  (14 @Repository classes)                     │
   │                                                                 │
   │   One repo per logical concern (PickPackage, Picker, Movement,  │
   │   Inventory, …). Uses Spring's JdbcClient with named-parameter  │
   │   SQL and a custom RecordRowMapper for record-typed results.    │
   │   Every method is @Transactional(readOnly = true).              │
   └─────────────────────────────────────────────────────────────────┘
                                  ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  DataSource layer  (DataSourcesConfig: three Hikari pools)      │
   │                                                                 │
   │   stockholmJdbcClient · inventoryJdbcClient · movementJdbcClient│
   │   Each pool: read-only, 5 connections max, init-SQL forces      │
   │   default_transaction_read_only = on.                           │
   └─────────────────────────────────────────────────────────────────┘
                                  ▼
   ┌─────────────────────────────────────────────────────────────────┐
   │  PostgreSQL 13  (three databases, multi-host failover URL)      │
   │  central-postgres13-{01,02}.qa2-sg.cld:5432, targetServerType=master │
   └─────────────────────────────────────────────────────────────────┘
```

### Source-tree layout

```
src/main/java/com/gdn/opsvision/mcp/
├── OpsvisionMcpApplication.java     (@SpringBootApplication entry)
├── config/
│   ├── DataSourcesConfig.java       (3 Hikari + 3 JdbcClient beans)
│   └── McpToolsConfig.java          (registers 19 @Tool methods)
├── tool/                            (19 service classes, @Service @Tool methods)
│   ├── DiagnosePickPackageTool.java
│   ├── DiagnosePickerQueueTool.java
│   ├── FindPickingTaskRequestsTool.java
│   ├── FindPickingTasksTool.java
│   ├── GetSalesOrderTool.java
│   ├── GetStockHistoryForItemTool.java
│   ├── InventoryForItemTool.java
│   ├── MovementHistoryTool.java
│   ├── PickListReadinessTool.java
│   ├── PickListTool.java
│   ├── PickPackageTool.java
│   ├── ReconciliationTool.java
│   ├── StockTraceTool.java
│   └── util/
│       └── IsoBound.java            (ISO date / datetime parser for tz-less timestamp params)
├── repository/                      (14 @Repository classes)
│   ├── InventoryRepository.java
│   ├── MovementRepository.java
│   ├── MovementSearchRepository.java
│   ├── PickListRepository.java
│   ├── PickPackageDiagnosisRepository.java
│   ├── PickPackageRepository.java
│   ├── PickerAccessRepository.java
│   ├── ReconciliationRepository.java
│   ├── SalesOrderRepository.java
│   ├── StockHistoryRepository.java
│   ├── StuckRulesRepository.java
│   ├── WarehouseDefectMapRepository.java
│   └── support/
│       └── RecordRowMapper.java     (record-aware row mapper, fixes pgjdbc Instant gap)
└── dto/                             (23 evidence DTOs + 9 lifecycle enums)
    ├── PickPackageEvidence.java     (header + handling units + sales orders)
    ├── PickPackageDiagnosisEvidence.java   (composite "why is this stuck?")
    ├── PickerQueueDiagnosisEvidence.java   (composite "why is this picker idle?")
    ├── ...
    └── SignalKind.java              (OPERATOR_OVERRIDE / BLOCKER_* / STAGE / CONTEXT)
```

---

## 4. The three datasources

Each domain DB has a single purpose and a single set of repositories. There is **no JPA / Hibernate** — only `JdbcClient` with hand-written named-parameter SQL.

| Bean qualifier | Database | DB # | Purpose | Schema highlights |
|---|---|---|---|---|
| `stockholmJdbcClient` | `stockholm_marunda_restore` | 413 | Pick-package, sales-order, picker, pick-list, packing-order state | `pick_package`, `sales_order`, `picker`, `pick_list`, `packing_order`, `picking_item`, `pick_list_details`, `warehouse_defect_map`, `picker_zone_group`, `zone_zone_group`, `zone` |
| `inventoryJdbcClient` | `warehouse_inventory_marunda` | 417 | Bin-level stock, stock history | `warehouse_item_master`, `bin`, `warehouse_item_bin_master`, `warehouse_physical_*_stock`, `stock_history` |
| `movementJdbcClient` | `warehouse_stock_movement_marunda` | 416 | WCS picking-task lifecycle | `picking_task`, `picking_task_request`, `picking_task_list`, status-partitioned shadow tables |

### The cross-DB join

There is no foreign key across DBs. The two stitching keys are:

- **`item.code`** (SKU code) — same string in every DB; joins inventory rows to stockholm picking demand.
- **`stock_trace_id`** (UUID) — written by stockholm when reservation is created, read across both inventory `stock_history` and movement `picking_task` to follow one unit of stock through its lifetime.

### Read-only enforcement (triple-layered)

Defined in `DataSourcesConfig` + `application.yml`, verified by `ReadOnlyEnforcementTest`:

```
1. Hikari    : read-only: true            → Connection.setReadOnly(true)
2. Init SQL  : SET default_transaction_   → server-side rejection of DML
                read_only = on
3. Spring TX : @Transactional(readOnly=    → annotation on every repo method
                true)
```

Postgres rejects DML with SQLState `25006` ("read_only_sql_transaction"). On a
DB where the role lacks `UPDATE` grants entirely, "permission denied for
table …" is the equivalent rejection. The integration test asserts both.

> **Why three layers?** Belt-and-suspenders for a service that an LLM agent
> drives. A single bug (forgetting `@Transactional`, a stale Hikari config,
> a misconfigured role) cannot punch through more than one layer.

---

## 5. The 19 tools

Tools are grouped by purpose. Each is a `@Tool`-annotated method on a
`@Service` class, registered via `McpToolsConfig.opsvisionTools(...)`.

### A) Single-entity fetch (slow, deep state)

| Tool | Returns | Primary use |
|---|---|---|
| `getPickPackage` | `PickPackageEvidence` (header + handling units + sales orders) | Snapshot of one PP for triage |
| `getPickList` | `PickListEvidence` (header + line-item details) | Snapshot of one pick list |
| `getSalesOrder` | `SalesOrderEvidence` (so + picking_items, keyed by `order_item_id`) | Upstream order context |
| `getInventoryForItem` | `InventoryForItemEvidence` (1–2 WIM rows + bins, with `physicalWarehouseCode`) | "Where does this SKU's stock physically live?" |

### B) Composite diagnose (multiple repos, derived signals)

| Tool | Returns | Question it answers |
|---|---|---|
| `diagnosePickPackage` | `PickPackageDiagnosisEvidence` (state, priority, allocations, source areas, replenishment, batch consolidation, packing order, movement-DB rows, **34 boolean signals**, picker-pool freshness) | "Why isn't this PP being picked / progressing?" |
| `diagnosePickerQueue` | `PickerQueueDiagnosisEvidence` (picker state, zone-group memberships, available work, sibling pickers, **13 boolean signals**) | "Why is THIS picker's queue empty?" |
| `evaluatePickListReadiness` | `PickListReadinessEvidence` (13 rule pass/fail with inputs) | "Is this PP truly stuck per the 13-rule support checklist?" |
| `reconcileInventoryVsReservation` | `InventoryReservationReconciliation` (per-SKU demand vs supply with divergence math) | "Is the picking demand satisfied by available stock?" |

### C) Lifecycle ledgers (warehouse-stock-movement chain)

| Tool | Returns | Primary use |
|---|---|---|
| `getMovementHistory` | `MovementHistoryEvidence` (every WCS task / task-request for a PP, sorted) | Full picking-side audit trail |
| `getStockHistoryForItem` | `StockHistoryEvidence` (windowed `stock_history` rows for a SKU at a site) | Per-item stock movement reconstruction |
| `getStockTrace` | `StockTraceEvidence` (every row sharing one `stock_trace_id`) | Follow one unit of stock end-to-end |

### D) Bulk search (filter then iterate)

| Tool | Returns | Primary use |
|---|---|---|
| `findPickingTasks` | `PickingTaskSearchEvidence` (filtered list of `picking_task` rows) | Find tasks matching status / time / picker / SKU |
| `findPickingTaskRequests` | `PickingTaskRequestSearchEvidence` (filtered task-request rows) | Find requests stuck in HOLD, etc. |
| `findPickPackages` | `PickPackageSearchEvidence` (filtered `pick_package` rows) | Generic micro-API search across PPs by status, picker, batch, zone, time |
| `findOrdersByLastProcessDate` | `OrdersByLastProcessDateEvidence` (active snapshot + terminal-in-window) | Operational dashboard view of sales-order state at a site |

### E) Site-wide scanners and aggregations

| Tool | Returns | Primary use |
|---|---|---|
| `findReservationDriftHotspots` | `ReservationDriftHotspotsEvidence` (WIM rows ranked by aggregate-vs-bins drift) | "Which SKUs at this site have phantom-reservation leaks?" |
| `findStockDiscrepancyOrigin` | `StockDiscrepancyOriginEvidence` (stock_traces where bin-net ≠ WIM-net for a SKU) | Forensic per-SKU origin of stock discrepancies |
| `findPpsBlockedByPickerAvailability` | `PpsBlockedByPickerAvailabilityEvidence` (per-zone groups of stuck PPs + picker pool) | "Which PPs are waiting for a free picker, grouped by zone?" |
| `aggregateSalesOrdersByStatus` | `SalesOrderStatusAggregateEvidence` (histogram of `last_status`) | "What's the breakdown of orders by status at this site right now?" |

### Cap-and-truncate everywhere

Every tool that returns variable-length lists has a hard cap with a truncation
flag. Concrete values across the codebase:

| Where | Cap | Truncation surfaced as |
|---|---:|---|
| `getInventoryForItem` per-WIM bins | 50 | `binsTruncated:true` |
| Eligible-picker pool per zone | 500 | (size implicit; bounded query) |
| Source-area → zone resolution | 50 | `resolvedZonesTruncated:true` |
| `findPickingTasks` / `findPickingTaskRequests` rows | 200 | `truncated:true` on response |
| `diagnosePickPackage` movement-DB rows | 20 | implicit (most-recent-first) |
| Picker-freshness `recentlyOnlinePickers` sample | 5 | implicit |
| Batch-consolidation siblings | 1000 | implicit |
| `getStockHistoryForItem` per-WIM events | configurable + 1 sentinel | `truncated:true` |

The principle: **the agent never gets an unbounded list, and an empty / capped
list is always distinguishable from the "no row exists" state.**

### Composition pattern

Composite tools (`diagnosePickPackage`, `diagnosePickerQueue`, `reconcile…`)
fan out to multiple repositories and assemble the result in pure Java —
**no chained DB roundtrips inside a single SQL statement**. The boundary
between SQL (in repos) and derivation (in tools) is strict:

- **Repo** returns row-shaped records carrying raw DB values + their nullable Java types.
- **Tool** attaches lifecycle stages, computes boolean signals, applies vacuous-true suppression, populates the public DTO.

This makes every signal **deterministic and testable in pure Java**.

---

## 6. Domain modeling

Two patterns dominate the DTO design.

### 6.1 Lifecycle stage enums

For every state machine in the SCPS schema, there is a typed Java enum that
mirrors it. This wraps brittle string columns in a value-checked, source-cited
enum the agent can pattern-match on.

| Enum | Mirrors | Source file cited |
|---|---|---|
| `OutboundStockLifecycleStage` | The 14-step `stock_action_type` chain (`OUT_RESERVE_*` … `DECREASE_BIN`) | warehouse-inventory enum |
| `HandlingUnitLifecycleStage` | 5 stages of `pick_package_handling_units.status` | stockholm `PickPackageHandlingUnitStatus` |
| `PickListLifecycleStage` | OPEN_UNCLAIMED / OPEN_CLAIMED / IN_PROGRESS / COMPLETE / CLOSED | `PickListStatus` + `picker_id` |
| `SalesOrderLifecycleStage` | 9 stages over 18 SOStatus ordinals | stockholm `SOStatus` |
| `PickingTaskLifecycleStage` | 8 stages of WCS `MovementTaskStatus` | warehouse-stock-movement |
| `PickingTaskRequestLifecycleStage` | 7 stages incl. `BLOCKED_HOLD` (Pattern A signal) | warehouse-stock-movement |
| `PackingOrderLifecycleStage` | Derived from `(active, claimed_date, good_issued_note)` | stockholm `PackingService` |
| `PickerOperationalStage` | Picker availability lifecycle | stockholm `PickerStatus` |

Every value in every enum carries a source-file reference so a regression-
prone enum can be regenerated from authoritative source code rather than
reverse-engineered.

### 6.2 Signals + SignalKind classification

Composite tools emit a flat block of pre-computed boolean signals
(e.g. `isCanceled`, `isInProblemSolve`, `hasReplenishmentDeficit`,
`hasOpenTaskRequest`). Each signal is classified by `SignalKind`:

```
OPERATOR_OVERRIDE   ← e.g. isRejected, isInactive (operator-set, dominates state)
BLOCKER_EXTERNAL    ← e.g. isStorageNotAvailable (waiting on upstream system)
BLOCKER_INTERNAL    ← e.g. hasEligiblePickersButNoneAvailable (capacity limit)
STAGE               ← e.g. isReadyForManualPicking, isPartialPackage
CONTEXT             ← e.g. isInBatchOrWave, hasMultipleSourceAreas
```

This **encodes the dominance hierarchy structurally**. The agent can lead its
narrative with `OPERATOR_OVERRIDE` signals (most decisive) and demote
`CONTEXT` signals (least decisive) without our server taking a position on
which specific signal "wins" within a kind. The mapping is enforced by a
reflective coverage test: **every** boolean field on a workflow-signals record
must have a `SignalKind` entry, or the test fails.

### 6.3 Vacuous-true suppression

For derived booleans that quantify over a (possibly empty) set, we force the
result to `false` when the input set is empty and emit a derivation note.

> Example: `isOnlyPickerForOwnZoneGroups` is technically `true` for a picker
> with no zone-groups (universal-quantifier-over-empty-set) but reads as
> "they're the only one" — wrong intuition. We force it `false` and let
> `hasNoZoneGroupMemberships` carry the meaning. The fixture-based unit
> tests assert this for every derived signal.

### 6.4 Evidence, not verdicts (the project's first principle)

Every DTO in `dto/` is named `*Evidence`. Tool descriptions repeat the line
*"Returns FACTS, not VERDICTS."*. Concretely:

- No tool returns "this PP is stuck because X". It returns the fact-set the
  agent uses to *form* "this PP is stuck because X".
- A row that exists is a fact. A row that's missing is a fact (`found:false`).
- A divergence is an arithmetic difference (`availableMinusRemaining = -3`),
  not a label ("UNDER-STOCKED").
- Lifecycle stages cite their source file so the agent can quote ground truth.

This keeps the server **stable across operational policy changes**. When ops
change the meaning of "stuck", the agent's prompt updates. The server
doesn't.

---

### 6.5 Pattern A / B / C — the named operational failure modes

Three recurring stuck-PP fingerprints are surfaced directly by `diagnosePickPackage`
without the agent having to chain tools. Each has a precise signal signature:

| Pattern | Failure mode | Diagnostic signature |
|---|---|---|
| **Pattern A** | WCS task-request stuck in `HOLD` — request was created in `warehouse_stock_movement` but never spawned child movement-tasks | `hasOpenTaskRequest=true` AND `hasOpenMovementTask=false` (visible in `movementTaskRequests` rows with status `HOLD`) |
| **Pattern B** | WCS phantom-close — task transitioned `PENDING_CLOSED → CLOSED` without the corresponding `DECREASE_BIN` event in inventory `stock_history` (precursor visible in pre-close task state) | `OutboundStockLifecycleStage.furthestStage = BIN_RESERVATION` for picking-relevant `stock_action_type` rows |
| **Pattern C** | WCS consolidation packing-handoff failure — handling unit reaches `PICKING_COMPLETE` but the `packing_order` was never created (or was hard-deleted) | `packingOrderMissing=true` (signal computed when `picking_status` is `REACHED_TO_QC` or `PICKING_COMPLETE` and `packingOrder.present=false`); `wcsConsolidationStuck=true` on the related handling unit |

These names are used throughout the codebase (commit messages, code comments,
signal documentation). The diagnose tool's evidence pack lets the agent
recognise each pattern from a single call rather than chaining
`diagnosePickPackage` → `getMovementHistory` → `getInventoryForItem`.

## 7. Cross-cutting: notable solved problems

### 7.1 pgjdbc 42.7.x rejects `java.time.Instant` (commit `ad11626`)

**Symptom.** Every diagnose call against a non-trivial PP failed with
`bad SQL grammar` — actual cause `conversion to class java.time.Instant
from timestamp[tz] not supported`.

**Cause.** pgjdbc deliberately doesn't support `getObject(idx, Instant.class)`
or `setObject(idx, Instant)`. Spring's stock `SimplePropertyRowMapper`
calls the read variant.

**Fix.** Custom `RecordRowMapper` (in `repository/support/`) that intercepts
`Instant` components and reads via the supported path: `OffsetDateTime` for
tz-aware columns, `Timestamp` with a UTC `Calendar` for tz-less columns
(matches Hibernate's UTC-write convention on the upstream services). All
30+ `.query(SomeRecord.class)` call sites were wrapped in
`RecordRowMapper.of(...)`. One Instant-bind site converted to
`OffsetDateTime`. Six unit tests pin the read path so re-introduction of
the bug fails fast in CI.

### 7.2 RESTRICTED stock = paired non-distribution warehouse (commit `26937c0`)

**Symptom.** `getInventoryForItem` returned RESTRICTED rows under
`siteCode=MAR-0000000001` with no hint of where the stock physically sat.

**Cause.** Stockholm's `WarehouseService.getStockIndicatorForWarehouse` folds
the sibling-warehouse code into `(parent, RESTRICTED)` before sending
`UpdateStockWebRequest` to warehouse-inventory. So a RESTRICTED row under
`MAR-0000000001` is actually stock at `MAN-0000000002` ("Marunda Non Dist").

**Fix.** New `WarehouseDefectMapRepository` lazy-loads the
parent ↔ defect mapping from stockholm and caches in memory.
`InventoryForItemEvidence.WarehouseItemMaster` gained a `physicalWarehouseCode`
field that resolves to the actual stockholm warehouse the stock lives in.

### 7.3 Eligible-picker freshness (commit `26fae99`)

**Symptom.** `breakdown: avail=0 busy=155 offline=324` answered "no AVAILABLE
picker right now" but didn't show whether OFFLINE pickers had logged off
5 minutes ago (back soon) or 8 days ago (stale pool, not coming back).

**Fix.** Two new fields on every `PickListAllocation` and `SourceAreaCoverage`:

- `PickerStatusFreshness` — 4-bucket histogram of OFFLINE pickers by `now − last_login_time`.
- `recentlyOnlinePickers` — top-5 OFFLINE pickers sorted by `last_login_time` DESC, with pre-computed `minutesSinceLastLogin`.

Computed in-memory from the existing `findEligiblePickersForZones` result —
no extra DB roundtrip. The agent now distinguishes "wait 15 min, someone's
on break" from "this zone is structurally short-staffed".

---

## 8. Deployment & runtime

### Runtime topology (current)

```
┌─────────────────────────────────────────────────┐
│  Local dev laptop                               │
│                                                 │
│  Java 21 (OpenJDK 25 also tested)               │
│  Spring Boot embedded Tomcat on :8081           │
│  Started via `mvn spring-boot:run`              │
│                                                 │
│  Env vars (passwords only):                     │
│    STOCKHOLM_DB_PASSWORD                        │
│    INVENTORY_DB_USER  + _PASSWORD               │
│    MOVEMENT_DB_USER   + _PASSWORD               │
└─────────────────────────────────────────────────┘
                      │
                      ▼  TLS-less direct (corp network)
       jdbc:postgresql://central-postgres13-{01,02}.qa2-sg.cld:5432
                                  ?targetServerType=master&ApplicationName=opsvision-mcp
```

### Configuration model

- **Static config** (`application.yml`): URL templates, pool sizes, init-SQL, log levels, MCP server name/version. Committed.
- **Secrets** (env vars): only the DB passwords. Never written to disk in this repo.
- **No profiles**: default profile only. `application-local.yml.example` exists for reference but is not loaded.

### Health & observability

- Spring Boot Actuator endpoints: `/actuator/health`, `/info`, `/metrics`. Exposed on the same port (`:8081/actuator`).
- Hikari emits pool-stat metrics; HikariCP-7 housekeeper warnings (`thread starvation`) appear after laptop sleep events but recover on next request.
- `org.springframework.ai.mcp` set to INFO; `com.gdn.opsvision` to DEBUG.

### MCP protocol detail

- **Transport**: Streamable HTTP (single endpoint `/mcp` accepts POST with `Mcp-Session-Id` header; SSE for streamed responses).
- **Protocol version**: server negotiates whatever the client offers — verified against `2025-03-26` and `2025-11-25` (current and prior MCP spec versions).
- **Capabilities advertised**: `tools.listChanged`, `resources`, `prompts`, `completions`, `logging`.
- **Sync mode** (`spring.ai.mcp.server.type: SYNC`): each tool method runs synchronously on a Tomcat servlet thread.
- **Tool input validation**: Spring AI's `ToolInputValidator` enforces the JSON-schema derived from `@ToolParam` annotations on every call. Unknown parameter names and missing required parameters are rejected before the method runs (e.g. calling `diagnosePickPackage` with the wrong param name `pickPackageCode` instead of `idOrCode` returns *"property 'pickPackageCode' is not defined in the schema"* without touching the database).
- **Error contract**: any uncaught exception thrown by a tool method is serialised into an MCP tool-result envelope with `isError:true` and the exception message as the `text` content. Database errors, validation errors, and not-found scenarios all share this envelope. The agent sees a structured error, not a HTTP 5xx.

---

## 9. Testing strategy

The project has 65 tests in 11 test classes.

| Layer | Test class | Style |
|---|---|---|
| **DTO / lifecycle** | `*LifecycleStageTest` × 7 | Pure-Java enum coverage (every status string maps; reflective tests) |
| **Tool signal logic** | `DiagnosePickPackageToolSignalsTest`, `DiagnosePickerQueueToolSignalsTest` | Fixture-based; mocks the rows, asserts derived signals + SignalKind classification |
| **Row mapping** | `RecordRowMapperTest` | Mockito on `ResultSet`; pins the Instant-read paths so the pgjdbc bug can't sneak back |
| **DB read-only enforcement** | `ReadOnlyEnforcementTest` | `@SpringBootTest`; opens real connections; sends `UPDATE`; asserts SQLState `25006` or perm-denied |

The read-only test is gated on env vars being present, so CI without DB
credentials skips it; local development with creds always runs it.

There are **no end-to-end MCP tests** today — verification is currently
manual via `curl` against the running server.

---

## 10. Key design decisions

| Decision | Rationale | Trade-off |
|---|---|---|
| **JdbcClient over JPA / Hibernate** | The schemas are stable, queries are inherently warehouse-domain-specific, and we never write. Hand-written SQL is more readable than entity-graph configuration. | More boilerplate per query; no second-level cache. |
| **One repo per logical concern, not per table** | Repos compose multi-table queries that match the SCPS investigation flow (`PickPackageDiagnosisRepository` joins picker, zone, allocation in one place). | Some repos are large (300+ lines); offset by row-shaped intermediate records. |
| **Three independent DataSources** | Each warehouse-domain DB is owned by a different upstream service. We don't want a connection pool exhausted on one DB to break queries to another. | 3× connection budget; small overhead. |
| **Evidence-not-verdict philosophy** | Stable surface across operational-policy changes; LLM is the variable, server is the constant. | Agent prompts must encode policy; verdicts have to live somewhere else. |
| **Lifecycle enums cite source files** | Future devs (and the agent) can verify the mapping against ground-truth SCPS source. | Maintenance burden when SCPS adds enum values. |
| **SignalKind dominance as enum rather than priority list** | Server doesn't commit to a specific priority order; agent picks the lead signal from the kinds it cares about. | Agent prompts must understand the kind taxonomy. |
| **Custom RecordRowMapper instead of changing DTO field types** | Public DTO contract stays as `Instant` (correct semantic); the workaround is contained in one mapper class. | One more file in the codebase; needs a regression test. |
| **No structured logging / tracing yet** | Single-tenant, low-volume tool used by one agent at a time today. Premature complexity. | Will need OTLP / structured logs once observability across agent sessions becomes important. |

---

## 11. What's next (roadmap, not promises)

1. **Mirror the freshness/sample fields on `diagnosePickerQueue.siblingPickerStatus`** — same agent-actionable insight, picker-rooted view.
2. **Putaway lifecycle enum** — currently blocked on a schema discrepancy (source enum has `OPEN/CLOSED` but QA2 contains `COMPLETED/PUTAWAY_STARTED`).
3. **Replenishment / IR lifecycle** — blocked on `IRStatus` mismatch (codes-up-to-17 in DB vs. 12-value enum in source).
4. **End-to-end MCP integration test** — hit `tools/call` over HTTP, assert structured response envelope.
5. **Observability** — request tracing with PP/PL ids on the MDC, OTLP export.
6. **CI** — currently relies on local `mvn test`; no GitHub Actions yet.

---

## Appendix A — Module dependency graph

```
                       OpsvisionMcpApplication
                                │
                                ▼
                          McpToolsConfig ──────────────────────┐
                                │                              │
                                ▼ (registers 19 @Tool methods) │
                  ┌─────────────────────────────────────┐      │
                  │              tool/                  │      │
                  └──────────────────┬──────────────────┘      │
                                     │                         │
                        ┌────────────┴────────────┐            │
                        ▼                         ▼            │
                ┌──────────────┐           ┌──────────────┐    │
                │ repository/  │           │     dto/     │    │
                └──────┬───────┘           └──────────────┘    │
                       │                          ▲            │
                       ▼                          │            │
              ┌──────────────────┐                │            │
              │ repository/      │                │            │
              │   support/       │                │            │
              │ RecordRowMapper  │                │            │
              └────────┬─────────┘                │            │
                       │                          │            │
                       ▼                          │            │
              ┌──────────────────┐                │            │
              │ DataSourcesConfig│ ←──────────────┴────────────┘
              │  3× HikariDS     │   (config beans wired by Spring)
              │  3× JdbcClient   │
              └──────────────────┘
```

## Appendix B — Tool call cheat-sheet (for slide / leave-behind)

```
diagnosePickPackage(idOrCode)
  → "Why isn't this PP picked?"  (one call, full evidence pack)

diagnosePickerQueue(pickerCode)
  → "Why is this picker idle?"    (sibling tool to the above)

evaluatePickListReadiness(idOrCode)
  → "Is this PP truly stuck per the 13-rule support checklist?"

reconcileInventoryVsReservation(idOrCode)
  → "Does the picking demand line up with available stock?"

getMovementHistory(idOrCode)
  → "Full WCS picking-task ledger for this PP."

getStockTrace(stockTraceId)
  → "Follow one stock_trace_id end-to-end across DBs."

getPickPackage / getPickList / getSalesOrder / getInventoryForItem
  → Single-entity deep snapshots.

findPickingTasks / findPickingTaskRequests
  → Bulk filtered search (status, time window, picker, sku, …).

getStockHistoryForItem
  → Windowed stock_history for one (skuCode, siteCode).
```

---

*Generated 2026-05-07. Code-of-record commit: `26fae99`.*
