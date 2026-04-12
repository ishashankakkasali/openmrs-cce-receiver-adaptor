# CCE Receiver Bridge Module — Design & Architecture Plan

> **Purpose:** A comprehensive plan to create an OpenMRS omod that replaces the standalone CCE Receiver Adaptor Spring Boot service. This module runs **inside** the OpenMRS O3 container, receives FHIR R4 payloads from upstream systems (RHIE / OpenHIM / SPICE / eBuzima), enriches them, and saves them into OpenMRS using internal Java service APIs — eliminating the external HTTP middleware entirely.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Solution Overview](#2-solution-overview)
3. [Architecture Comparison](#3-architecture-comparison)
4. [System Architecture](#4-system-architecture)
5. [Module Structure](#5-module-structure)
6. [Component Design](#6-component-design)
7. [Enrichment Pipeline (Ported)](#7-enrichment-pipeline-ported)
8. [Complexity Analysis](#8-complexity-analysis)
9. [What Changes vs. What Stays the Same](#9-what-changes-vs-what-stays-the-same)
10. [Risk Assessment](#10-risk-assessment)
11. [Development Phases](#11-development-phases)
12. [Testing Strategy](#12-testing-strategy)
13. [Deployment](#13-deployment)
14. [Decision Matrix: Standalone vs. Bridge Module](#14-decision-matrix-standalone-vs-bridge-module)
15. [Recommendation](#15-recommendation)

---

## 1. Problem Statement

The current **CCE Receiver Adaptor** is a standalone Spring Boot service (~3,900 lines) that sits between upstream FHIR sources and OpenMRS. It:

1. Receives FHIR R4 Bundles/resources via HTTP
2. Enriches them (resolve references, concepts, identifiers, encounter types, visits, required fields, sub-element IDs)
3. Sends them to OpenMRS via HTTP (FHIR R4 or REST v1 endpoints)

**This creates operational overhead:**

| Concern | Impact |
|---------|--------|
| Extra container to deploy, monitor, and scale | Ops complexity |
| 13 distinct HTTP call patterns from adaptor → OpenMRS | Latency (~10-50ms per call) |
| Authentication configured twice (adaptor inbound + adaptor → OpenMRS outbound) | Config complexity |
| Network failures between adaptor and OpenMRS | Reliability risk |
| Adaptor's in-memory caches (concepts, references, encounter types) duplicate OpenMRS data | Memory waste |
| Spring Boot + HAPI FHIR + Spring Security stack overhead | ~200MB+ RAM |

**Question:** Can we move this enrichment + save logic into an OpenMRS module (omod) that runs inside the O3 container, similar to the CCE Event Bridge Module on the emitter side?

---

## 2. Solution Overview

A **CCE Receiver Bridge Module** — an OpenMRS omod JAR that:

1. **Exposes an HTTP endpoint** inside OpenMRS: `POST /openmrs/ws/rest/v1/ccereceiver/fhir`
2. **Receives FHIR R4 payloads** (Bundles or standalone resources) from upstream systems
3. **Enriches them** using the same pipeline as the standalone adaptor, BUT using OpenMRS Java APIs directly instead of HTTP calls
4. **Saves resources** via OpenMRS service layer (PatientService, EncounterService, etc.) OR via the co-located FHIR2 Module's internal Java API

### Key Advantage: HTTP → Java API

| Standalone Adaptor | Bridge Module |
|---|---|
| `GET /concept?source=LOINC&code=85354-9` (HTTP) | `Context.getConceptService().getConceptByMapping("85354-9", "LOINC")` (Java) |
| `GET /Patient?identifier=NID-123` (HTTP) | `Context.getPatientService().getPatients(identifier)` (Java) |
| `GET /encountertype?v=default` (HTTP) | `Context.getEncounterService().getAllEncounterTypes()` (Java) |
| `POST /ws/fhir2/R4/Patient` (HTTP) | `fhirPatientService.create(patient)` (Java, FHIR2 internal API) |
| `GET /visit?patient={uuid}` (HTTP) | `Context.getVisitService().getActiveVisitsByPatient(patient)` (Java) |
| `POST /visit` (HTTP) | `Context.getVisitService().saveVisit(visit)` (Java) |
| `GET /location?tag=Login+Location` (HTTP) | `Context.getLocationService().getLocationsByTag(tag)` (Java) |
| `GET /patientidentifiertype` (HTTP) | `Context.getPatientService().getAllPatientIdentifierTypes()` (Java) |

**Result:** 13 HTTP call patterns → 0 HTTP calls. All lookups become in-JVM method calls (~microseconds instead of ~milliseconds).

---

## 3. Architecture Comparison

### Current: Standalone Adaptor (External Middleware)

```
Upstream (RHIE / OpenHIM / SPICE / eBuzima)
    │
    │  HTTP POST /api/v1/fhir (FHIR JSON)
    ▼
┌──────────────────────────────────┐
│  CCE Receiver Adaptor            │   ← Separate container (~200MB RAM)
│  (Spring Boot, port 8083)        │
│                                  │
│  [Split] → [Enrich] → [Route]   │
│                                  │
│  ┌─ 13 HTTP calls per resource ──┤──────────┐
│  │  concepts, references, types  │          │
│  │  identifiers, visits, save    │          │
└──┼───────────────────────────────┘          │
   │                                          │
   │  HTTP (FHIR R4 + REST v1)               │
   ▼                                          ▼
┌─────────────────────────────────────────────────┐
│  OpenMRS O3 Container                           │
│  ┌──────────┐  ┌───────────┐  ┌──────────────┐ │
│  │ FHIR2    │  │ REST      │  │ OpenMRS      │ │
│  │ Module   │  │ Module    │  │ Backend      │ │
│  └──────────┘  └───────────┘  └──────────────┘ │
└─────────────────────────────────────────────────┘
```

### Proposed: Bridge Module (Inside OpenMRS)

```
Upstream (RHIE / OpenHIM / SPICE / eBuzima)
    │
    │  HTTP POST /openmrs/ws/rest/v1/ccereceiver/fhir (FHIR JSON)
    ▼
┌─────────────────────────────────────────────────────────┐
│  OpenMRS O3 Container                                   │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │ 🆕 CCE Receiver Bridge Module (omod JAR)          │  │
│  │                                                    │  │
│  │  [Split] → [Enrich] → [Save]                      │  │
│  │       │         │         │                        │  │
│  │       │    Java API calls (in-JVM, no HTTP)        │  │
│  │       ▼         ▼         ▼                        │  │
│  │  ConceptService  PatientService  FHIR2 Services    │  │
│  │  EncounterService  VisitService  LocationService   │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  ┌──────────┐  ┌───────────┐  ┌──────────────┐         │
│  │ FHIR2    │  │ Event     │  │ OpenMRS      │         │
│  │ Module   │  │ Module    │  │ Backend      │         │
│  └──────────┘  └───────────┘  └──────────────┘         │
└─────────────────────────────────────────────────────────┘
```

---

## 4. System Architecture

### End-to-End Data Flow

```
1. Upstream system sends FHIR R4 Bundle/resource via HTTP POST
2. OpenMRS REST module routes to CCE Receiver Bridge's REST resource
3. Bridge module splits Bundle into individual ResourceEntry objects
4. For each resource (sorted by dependency order):
   a. Resolve non-UUID references → OpenMRS UUIDs (via PatientService, etc.)
   b. Resolve concept codes (LOINC/SNOMED → OpenMRS concept UUID) (via ConceptService)
   c. Resolve encounter types (via EncounterService)
   d. Enrich required fields (defaults for missing mandatory fields)
   e. Enrich patient identifiers (LuhnMod30 + source ID promotion) (via PatientService)
   f. Inject sub-element UUIDs (FhirElementIdEnricher)
   g. Link encounters to visits (via VisitService)
   h. Save via FHIR2 Module internal Java API (or OpenMRS service layer for orders)
5. Return ProcessingResponse JSON with per-resource results
```

### Authentication

The module runs inside OpenMRS, so inbound requests flow through OpenMRS's own authentication:

| Current Adaptor | Bridge Module |
|---|---|
| Custom SecurityConfig (Basic + JWT) | OpenMRS REST module auth (session/Basic auth) |
| Separate OAuth2 token provider for outbound calls | Not needed — internal Java calls, authenticated context |
| Two auth configurations | One — OpenMRS native |

The REST resource would require an authenticated OpenMRS session (same as any REST v1 endpoint). Upstream systems authenticate to OpenMRS the same way they would for any OpenMRS REST call.

### Resource Save Strategy

Two approaches for persisting enriched FHIR resources:

#### Option A: Via FHIR2 Module Internal Java API (Recommended)

```java
// The FHIR2 module exposes service interfaces like:
@Autowired FhirPatientService fhirPatientService;

// Parse enriched FHIR JSON → HAPI FHIR resource object
Patient patient = fhirContext.newJsonParser().parseResource(Patient.class, enrichedJson);

// Save via FHIR2 service (handles FHIR↔OpenMRS translation internally)
Patient saved = fhirPatientService.create(patient);
```

**Pros:** Reuses FHIR2 module's existing FHIR↔OpenMRS translation layer. Consistent with how FHIR2 module handles API requests.

**Cons:** Still subject to FHIR2 module limitations (orders not supported, etc.). For orders, fall back to Option B.

#### Option B: Via OpenMRS Service Layer (for Orders + Fallback)

```java
// For ServiceRequest → TestOrder (not supported by FHIR2)
OrderService orderService = Context.getOrderService();
TestOrder order = new TestOrder();
order.setConcept(conceptService.getConceptByUuid(conceptUuid));
order.setPatient(patientService.getPatientByUuid(patientUuid));
// ... map all fields
orderService.saveOrder(order, null);
```

**Pros:** Full access to OpenMRS data model. No FHIR2 limitations.

**Cons:** Manual FHIR→OpenMRS mapping (duplicates what FHIR2 module does).

#### Hybrid Strategy (Recommended)

| Resource Type | Save Method |
|---|---|
| Patient, Encounter, Observation, Condition, Location, Practitioner, Medication, AllergyIntolerance, Immunization, DiagnosticReport, Task, Procedure | **FHIR2 internal Java API** |
| ServiceRequest → TestOrder | **OpenMRS OrderService** (direct) |
| MedicationRequest → DrugOrder | **OpenMRS OrderService** (direct) |

This mirrors the standalone adaptor's FHIR-vs-REST routing, but without any HTTP.

---

## 5. Module Structure

### Maven Project Layout

```
openmrs-module-cce-receiver-bridge/
├── pom.xml                                    # Maven parent POM
│
├── api/                                       # API module (non-web logic)
│   ├── pom.xml
│   └── src/main/java/org/openmrs/module/ccereceiverbridge/
│       │
│       ├── CceReceiverBridgeConfig.java       # Configuration from global properties
│       │
│       ├── model/
│       │   ├── ResourceEntry.java             # record(resourceType, resourceJson, method, fullUrl)
│       │   ├── RoutingResult.java             # record(resourceType, resourceId, status, httpStatus, ...)
│       │   └── ProcessingResponse.java        # record(timestamp, totalEntries, succeeded, failed, results)
│       │
│       ├── splitter/
│       │   └── BundleSplitter.java            # Bundle/standalone → List<ResourceEntry>
│       │
│       ├── enricher/
│       │   ├── ConceptResolver.java           # LOINC/SNOMED → OpenMRS concept UUID (via ConceptService)
│       │   ├── ReferenceResolver.java         # Non-UUID refs → OpenMRS UUIDs (via PatientService, etc.)
│       │   ├── EncounterTypeResolver.java     # Encounter type text → UUID (via EncounterService)
│       │   ├── RequiredFieldEnricher.java     # Missing required field defaults
│       │   ├── PatientIdentifierEnricher.java # LuhnMod30 + source ID promotion (via PatientService)
│       │   ├── ElementIdEnricher.java         # UUID injection for sub-elements
│       │   └── VisitLinker.java               # Visit auto-creation + linkage (via VisitService)
│       │
│       ├── saver/
│       │   ├── FhirResourceSaver.java         # Save via FHIR2 internal API
│       │   └── OrderSaver.java                # Save ServiceRequest/MedicationRequest via OrderService
│       │
│       ├── router/
│       │   └── ResourceRouter.java            # Dependency ordering + enrichment pipeline + save dispatch
│       │
│       └── processor/
│           └── InboundProcessor.java          # Orchestrator: split → route → aggregate
│
├── omod/                                      # OMOD module (web layer)
│   ├── pom.xml
│   └── src/main/
│       ├── java/org/openmrs/module/ccereceiverbridge/
│       │   ├── web/
│       │   │   └── CceReceiverResource.java   # REST resource: POST /ccereceiver/fhir
│       │   └── CceReceiverBridgeActivator.java # Module lifecycle (start/stop)
│       └── resources/
│           ├── config.xml                     # OpenMRS module descriptor
│           └── moduleApplicationContext.xml   # Spring bean wiring
│
└── README.md
```

**Estimated file count:** ~18 Java source files + 2 XML configs + 2 POMs

---

## 6. Component Design

### 6.1 REST Resource — `CceReceiverResource.java`

Exposes the inbound HTTP endpoint within OpenMRS:

```
POST /openmrs/ws/rest/v1/ccereceiver/fhir
Content-Type: application/fhir+json (or application/json)
Headers: X-Correlation-ID (optional), X-Source-System (optional)
Body: FHIR R4 Bundle or standalone resource
```

Response:
```json
{
  "timestamp": "2026-04-09T10:30:00Z",
  "totalEntries": 3,
  "succeeded": 3,
  "failed": 0,
  "results": [...]
}
```

HTTP status: `202 Accepted` (all succeed) or `207 Multi-Status` (partial failure).

Implemented as a `DelegatingCrudResource` or plain `@Controller`-style REST resource following OpenMRS REST module conventions.

### 6.2 Configuration — `CceReceiverBridgeConfig.java`

Configuration via OpenMRS Global Properties (no separate YAML needed):

| Global Property | Description | Default |
|---|---|---|
| `ccereceiverbridge.enabled` | Enable/disable the receiver | `true` |
| `ccereceiverbridge.identifier.type.name` | Primary identifier type name | `OpenMRS ID` |
| `ccereceiverbridge.location.uuid` | Override location UUID (auto-discovered if blank) | `` |
| `ccereceiverbridge.visit.type.name` | Visit type for auto-creation | `Facility Visit` |
| `ccereceiverbridge.actions.create` | Enable resource creation | `true` |
| `ccereceiverbridge.actions.update` | Enable resource updates | `true` |
| `ccereceiverbridge.actions.delete` | Enable resource deletion | `false` |

### 6.3 Module Activator — `CceReceiverBridgeActivator.java`

OpenMRS module lifecycle:
- `started()` — Log startup, validate config, discover location/identifier types/visit types
- `stopped()` — Cleanup

### 6.4 Enrichment Pipeline

Same 5-step pipeline as the standalone adaptor, but using Java APIs:

```
[1] ReferenceResolver.resolve()         → PatientService, etc.
[2] ConceptResolver.resolve()           → ConceptService
[3] EncounterTypeResolver.resolve()     → EncounterService
[4] RequiredFieldEnricher.enrich()      → Pure logic (no API calls)
[5] PatientIdentifierEnricher.enrich()  → PatientService
[6] ElementIdEnricher.enrich()          → Pure logic (no API calls)
[7] VisitLinker.ensureVisitLinked()     → VisitService
```

### 6.5 Resource Save Path

```
ResourceRouter
  │
  ├── FHIR resources → FhirResourceSaver
  │     Uses FHIR2 Module internal Java API:
  │     - FhirPatientService.create(patient)
  │     - FhirEncounterService.create(encounter)
  │     - FhirObservationService.create(observation)
  │     - etc.
  │
  └── Orders → OrderSaver
        Uses OpenMRS OrderService:
        - ServiceRequest → TestOrder → OrderService.saveOrder()
        - MedicationRequest → DrugOrder → OrderService.saveOrder()
```

---

## 7. Enrichment Pipeline (Ported)

### What Stays Identical (Pure JSON Logic)

These components are purely algorithmic — they operate on JSON trees with no external dependencies:

| Component | Lines | Porting Effort | Changes |
|---|---|---|---|
| **RequiredFieldEnricher** | 166 | Copy as-is | None — pure Jackson JSON logic |
| **ElementIdEnricher** | 98 | Copy as-is | None — pure UUID injection logic |
| **BundleSplitter** | 89 | Copy as-is | None — pure HAPI FHIR parsing |
| **LuhnMod30 algorithm** | ~40 | Copy as-is | None — pure math |

**Total: ~393 lines, zero changes needed.**

### What Gets Simpler (HTTP → Java API)

These components currently make HTTP calls that become direct Java service calls:

| Component | Current (HTTP) | Bridge (Java API) | Simplification |
|---|---|---|---|
| **ConceptResolver** (281 lines) | `GET /concept?source&code` via RestClient | `ConceptService.getConceptByMapping(code, source)` | Eliminate HTTP client, caching, error handling. ~60% less code |
| **ReferenceResolver** (544 lines) | `GET /{Type}?identifier=X` via RestClient | `PatientService.getPatients(identifier)` etc. | Eliminate HTTP client, JSON response parsing. ~40% less code |
| **EncounterTypeResolver** (part of ReferenceResolver) | `GET /encountertype?v=default` via RestClient | `EncounterService.getAllEncounterTypes()` | Trivial — one method call |
| **VisitLinker** (267 lines) | `GET /visit?patient&includeInactive=false` + `POST /visit` | `VisitService.getActiveVisitsByPatient()` + `VisitService.saveVisit()` | Eliminate HTTP, JSON construction. ~50% less code |
| **PatientIdentifierEnricher** (491 lines) | Uses DiscoveredConfig (populated via HTTP at startup) | `PatientService.getAllPatientIdentifierTypes()` directly | Eliminate config discovery HTTP. ID generation logic unchanged. ~20% less code |
| **ConfigDiscovery** (434 lines) | 5 HTTP calls at startup | Direct Java API calls | **Eliminated entirely** — services are always available in-JVM |

### What Gets Eliminated

| Component | Why Eliminated |
|---|---|
| **OpenMrsFhirClient** (161 lines) | No HTTP client needed — save via FHIR2 internal API |
| **OpenMrsRestClient** (132 lines) | No HTTP client needed — save via OrderService directly |
| **RestClientConfig** (two RestClient beans) | No outbound HTTP |
| **CapabilityStatementCache** (156 lines) | No need to query `/metadata` — we know what the FHIR2 module supports at compile time |
| **SourceIdMappingStore** (83 lines) | Can use PatientService.getPatients(identifier) for lookups instead of maintaining separate map |
| **OAuth2TokenProvider** | No outbound auth |
| **SecurityConfig** | OpenMRS handles auth natively |
| **FhirToRestTransformer** (192 lines) | For orders, map FHIR→OpenMRS domain objects directly (no intermediate REST JSON) |
| **Spring Retry annotations** | In-JVM calls don't have transient network failures |
| **Micrometer metrics** | Use OpenMRS's built-in logging (or add lightweight counters) |

### What Gets Replaced

| Standalone Component | Bridge Replacement |
|---|---|
| `FhirToRestTransformer` (FHIR JSON → REST JSON) | `OrderSaver` (FHIR JSON → OpenMRS Order domain objects directly) |
| `OpenMrsConfigDiscovery` (HTTP-based startup discovery) | Direct service calls in Activator startup (1/4 the code) |
| `DiscoveredConfig` (mutable cache of HTTP-discovered values) | Thinner config holder — most values fetched on-demand from services |

---

## 8. Complexity Analysis

### Standalone Adaptor Metrics

| Metric | Value |
|---|---|
| Main source files | 18 |
| Main source lines | ~3,900 |
| Public methods | 53 |
| Private methods | 88 |
| Test files | 14 |
| Test lines | ~2,919 |
| Test methods | 179 |
| External HTTP call patterns | 13 |
| Dependencies | Spring Boot + HAPI FHIR + Spring Security + Spring Retry + Micrometer |

### Estimated Bridge Module Metrics

| Metric | Value | Delta |
|---|---|---|
| Main source files | ~14 | -4 (eliminated HTTP clients, security, retry) |
| Main source lines | **~2,200** | **-44%** (HTTP → Java API simplification) |
| Public methods | ~35 | -34% |
| Private methods | ~50 | -43% |
| Test files | ~10 | -4 (no security tests, no HTTP client tests) |
| Test lines | ~1,800 | -38% |
| Test methods | ~110 | -39% |
| External HTTP call patterns | **0** | **-100%** |
| Dependencies | OpenMRS Module SDK + HAPI FHIR (comes with FHIR2 module) |

### Complexity Per Component (Estimated)

| Component | Standalone Lines | Bridge Lines | Effort | Difficulty |
|---|---|---|---|---|
| BundleSplitter | 89 | 89 | Copy | ⬜ Trivial |
| RequiredFieldEnricher | 166 | 166 | Copy | ⬜ Trivial |
| ElementIdEnricher | 98 | 98 | Copy | ⬜ Trivial |
| ConceptResolver | 281 | ~120 | Rewrite | 🟨 Medium |
| ReferenceResolver | 544 | ~320 | Rewrite | 🟥 High |
| PatientIdentifierEnricher | 491 | ~400 | Adapt | 🟥 High |
| VisitLinker | 267 | ~130 | Rewrite | 🟨 Medium |
| ResourceRouter | 219 | ~180 | Adapt | 🟨 Medium |
| FhirResourceSaver | — | ~150 | New | 🟨 Medium |
| OrderSaver | 192 (transformer) | ~200 | Rewrite | 🟥 High |
| InboundProcessor | 80 | ~70 | Adapt | ⬜ Trivial |
| CceReceiverResource | 56 | ~80 | Rewrite | 🟨 Medium |
| Config + Activator | 529 | ~150 | Rewrite | 🟨 Medium |
| Models | ~50 | ~50 | Copy | ⬜ Trivial |
| **TOTAL** | **~3,900** | **~2,200** | — | — |

### Effort Breakdown

| Category | Hours (Est.) | % of Total |
|---|---|---|
| **Trivial (copy):** BundleSplitter, RequiredFieldEnricher, ElementIdEnricher, Models | 4-8h | 5% |
| **Medium (rewrite):** ConceptResolver, VisitLinker, ResourceRouter, FhirResourceSaver, REST resource, Config | 40-60h | 45% |
| **High (complex rewrite):** ReferenceResolver, PatientIdentifierEnricher, OrderSaver | 40-60h | 35% |
| **Testing:** Unit tests, integration tests, E2E with real OpenMRS | 30-40h | 15% |
| **TOTAL** | **~120-170h** | |

---

## 9. What Changes vs. What Stays the Same

### Stays the Same ✅

1. **LuhnMod30 algorithm** — identical character set, computation
2. **Required field defaults map** — same 13 resource types, same defaults
3. **Array fields needing IDs** — same 18 field names
4. **Dependency ordering** — same priority map (Patient=1, ..., default=10)
5. **System-to-source concept mapping** — same 13 FHIR system → OpenMRS source entries
6. **CODEABLE_CONCEPT_FIELDS** — same set of fields to process
7. **Encounter type matching** — same 3-tier strategy (exact → prefix → contains)
8. **Bundle splitting** — same rules for Bundle vs standalone, UUID detection
9. **Source identifier promotion logic** — same matching strategy (last segment → contains)
10. **Response format** — same ProcessingResponse structure

### Changes ⚡

1. **Concept lookup:** `RestClient.get("/concept?source=X&code=Y")` → `ConceptService.getConceptByMapping(code, source)`
2. **Reference resolution:** `RestClient.get("/{Type}?identifier=X")` → `PatientService.getPatients(identifier)`, etc.
3. **Encounter type lookup:** HTTP → `EncounterService.getAllEncounterTypes()`
4. **Visit management:** HTTP GET/POST → `VisitService` direct calls
5. **Resource saving:** HTTP POST/PUT/DELETE → FHIR2 service internal API + OrderService
6. **Configuration:** `application.yml` + `@ConfigurationProperties` → OpenMRS Global Properties
7. **Authentication:** Custom SecurityConfig → OpenMRS native REST auth
8. **Retry:** Spring Retry `@Retryable` → not needed (in-JVM calls don't have transient network failures)
9. **Metrics:** Micrometer/Prometheus → OpenMRS logging + optional simple counters
10. **Identifier type creation:** `RestClient.post("/patientidentifiertype")` → `PatientService.savePatientIdentifierType()`

### Eliminated 🗑️

1. RestClientConfig (two RestClient beans)
2. OAuth2TokenProvider
3. SecurityConfig (Custom inbound auth)
4. CapabilityStatementCache
5. SourceIdMappingStore (use PatientService for lookups)
6. OpenMrsFhirClient
7. OpenMrsRestClient
8. FhirToRestTransformer (replaced by direct OrderSaver)
9. OpenMrsConfigDiscovery (replaced by direct service calls)
10. Spring Retry configuration
11. GlobalExceptionHandler (OpenMRS REST module handles errors)
12. DiagnosticsController (capabilities/routing-table endpoints)

---

## 10. Risk Assessment

### High Risk 🔴

| Risk | Impact | Mitigation |
|---|---|---|
| **FHIR2 Module internal API stability** — These are internal Java interfaces, not public REST APIs. They may change between FHIR2 module versions. | Module breaks on FHIR2 upgrade | Pin FHIR2 module version in `config.xml` `<require_modules>`. Add integration tests against specific FHIR2 version. |
| **OpenMRS service layer exceptions** — Internal services throw `APIException`, `DAOException`, etc. which differ from HTTP error responses. Different error handling needed. | Uncaught exceptions crash request processing | Comprehensive try-catch in router/saver. Map OpenMRS exceptions → RoutingResult.failure() |
| **Transaction boundaries** — OpenMRS services operate within Hibernate transactions. A failed save mid-Bundle could leave partial state. | Data inconsistency on partial failure | Use `@Transactional` carefully — process each resource in its own transaction (match standalone adaptor's per-resource semantics) |
| **Module class loading** — OpenMRS module class loader is isolated. Accessing FHIR2 module's internal services requires correct Maven dependencies and `require_modules`. | ClassNotFoundException at runtime | Test extensively in real O3 environment. Verify class visibility. |

### Medium Risk 🟡

| Risk | Impact | Mitigation |
|---|---|---|
| **OpenMRS Context threading** — `Context.getXxxService()` requires an authenticated OpenMRS context on the current thread. REST resources have this automatically, but any async processing does not. | `ContextNotActiveException` | Process synchronously within the REST request thread. No async. |
| **HAPI FHIR version mismatch** — The standalone adaptor uses HAPI 7.4.0. The FHIR2 module bundles its own HAPI version (may differ). | `NoSuchMethodError` at runtime | Do NOT bundle HAPI FHIR in the omod. Use `<scope>provided</scope>` and rely on the FHIR2 module's HAPI. |
| **Performance under load** — The standalone adaptor can be scaled independently. The bridge module shares resources with OpenMRS. | OpenMRS performance degradation under high inbound traffic | Implement request throttling via global property. Monitor JVM heap. |
| **Testing difficulty** — OpenMRS module tests require the OpenMRS test framework (in-memory H2 DB, full Spring context). Slower than standalone Mockito tests. | Longer test cycles | Use Mockito for enricher unit tests (no OpenMRS context needed). Use BaseModuleContextSensitiveTest only for integration tests. |

### Low Risk 🟢

| Risk | Impact | Mitigation |
|---|---|---|
| **LuhnMod30 algorithm correctness** — A port could introduce bugs in the check digit calculation. | OpenMRS rejects patients | Copy algorithm verbatim. Test with known valid IDs. |
| **JSON processing library** — OpenMRS uses Jackson (same as standalone adaptor). | None | Jackson ObjectMapper available via Spring context. |

---

## 11. Development Phases

### Phase 1: Scaffold + Pure Logic (~2 weeks)

**Goal:** Project structure + all components that require zero OpenMRS APIs.

| Task | Description | Source |
|---|---|---|
| Maven project scaffold | Parent POM + api/ + omod/ modules | New |
| config.xml + moduleApplicationContext.xml | Module descriptor, Spring wiring | New |
| Activator | Startup logging | New |
| ResourceEntry, RoutingResult, ProcessingResponse | Model records/classes | Copy from standalone |
| BundleSplitter | Bundle → List<ResourceEntry> | Copy from standalone |
| RequiredFieldEnricher | Default field injection | Copy from standalone |
| ElementIdEnricher | Sub-element UUID injection | Copy from standalone |
| LuhnMod30 utility | Check digit generation | Copy from standalone |
| Unit tests for all above | Mockito-based, no OpenMRS context | Adapt from standalone |

**Deliverable:** Buildable omod that compiles. ~400 lines of pure logic ported.

### Phase 2: Enrichers with OpenMRS APIs (~3 weeks)

**Goal:** Port all enrichers that need OpenMRS service calls.

| Task | Description | Complexity |
|---|---|---|
| ConceptResolver | `ConceptService.getConceptByMapping()` + system-to-source map | Medium |
| ReferenceResolver | Multi-service reference lookup (Patient, Practitioner, Location, etc.) | High |
| EncounterTypeResolver | `EncounterService.getAllEncounterTypes()` + fuzzy matching | Medium |
| PatientIdentifierEnricher | LuhnMod30 + `PatientService` for identifier type management | High |
| VisitLinker | `VisitService.getActiveVisitsByPatient()` + create Visit | Medium |
| Integration tests | `BaseModuleContextSensitiveTest` with H2 DB | Medium |

**Deliverable:** Full enrichment pipeline working against in-memory OpenMRS. ~1,200 lines.

### Phase 3: Resource Saving + Router (~2 weeks)

**Goal:** Save enriched resources into OpenMRS.

| Task | Description | Complexity |
|---|---|---|
| FhirResourceSaver | FHIR2 internal API (create/update/delete) for standard resources | Medium |
| OrderSaver | FHIR ServiceRequest → TestOrder, MedicationRequest → DrugOrder | High |
| ResourceRouter | Dependency ordering + enrichment pipeline + save dispatch | Medium |
| InboundProcessor | Orchestrator: split → route → aggregate | Low |

**Deliverable:** End-to-end processing of FHIR Bundles. ~500 lines.

### Phase 4: REST Endpoint + Integration (~1 week)

**Goal:** HTTP endpoint + end-to-end testing.

| Task | Description | Complexity |
|---|---|---|
| CceReceiverResource | REST resource implementing POST /ccereceiver/fhir | Medium |
| CceReceiverBridgeConfig | Global property bindings | Low |
| Activator enhancements | Startup validation, config logging | Low |
| E2E integration tests | Full Bundle processing in test OpenMRS | High |

**Deliverable:** Deployable omod. ~150 lines.

### Phase 5: Testing + Hardening (~1 week)

**Goal:** Comprehensive test coverage + error handling.

| Task | Description |
|---|---|
| Edge case tests | Malformed FHIR, missing fields, unknown concepts |
| Error handling | Map all OpenMRS exceptions → RoutingResult.failure() |
| Performance testing | Bundle with 50+ resources |
| Documentation | README, global property descriptions |
| E2E with real O3 | Docker-based test against real O3 instance |

---

## 12. Testing Strategy

### Unit Tests (No OpenMRS Context)

For pure-logic components — fast, Mockito-based:

| Component | Test Approach | Est. Tests |
|---|---|---|
| BundleSplitter | Parse JSON, verify ResourceEntry list | ~8 |
| RequiredFieldEnricher | Verify defaults injected per resource type | ~23 |
| ElementIdEnricher | Verify UUID IDs injected in arrays | ~9 |
| LuhnMod30 | Known input/output pairs | ~5 |
| ResourceEntry, RoutingResult, ProcessingResponse | Factory methods, equality | ~5 |

**~50 tests, no OpenMRS dependency**

### Service-Mocked Tests

Mock OpenMRS services with Mockito:

| Component | Mocked Services | Est. Tests |
|---|---|---|
| ConceptResolver | ConceptService | ~11 |
| ReferenceResolver | PatientService, EncounterService, LocationService, etc. | ~20 |
| EncounterTypeResolver | EncounterService | ~5 |
| PatientIdentifierEnricher | PatientService | ~12 |
| VisitLinker | VisitService | ~15 |
| ResourceRouter | FhirResourceSaver, OrderSaver | ~13 |
| InboundProcessor | ResourceRouter, BundleSplitter | ~5 |

**~81 tests, Mockito-mocked services**

### Integration Tests (OpenMRS Context)

Using `BaseModuleContextSensitiveTest` with in-memory H2 DB:

| Test Scope | Est. Tests |
|---|---|
| Full enrichment pipeline (real ConceptService, real PatientService) | ~10 |
| FHIR2 module save (FhirPatientService.create) | ~5 |
| OrderSaver (OrderService.saveOrder) | ~4 |
| REST resource (MockMvc-style via OpenMRS test framework) | ~5 |
| Error handling (invalid FHIR, missing concepts) | ~5 |

**~29 integration tests**

### Total Estimated Tests: ~160

(vs. 179 in standalone adaptor — fewer because HTTP client/retry/security tests eliminated)

---

## 13. Deployment

### Build

```bash
cd openmrs-module-cce-receiver-bridge
mvn clean package -DskipTests
# → omod/target/ccereceiverbridge-1.0.0.omod
```

### Add to O3 Distro

In `distro/distro.properties`:
```properties
omod.ccereceiverbridge=1.0.0
omod.ccereceiverbridge.groupId=org.openphc.cce
```

In `distro/pom.xml`:
```xml
<ccereceiverbridge.version>1.0.0</ccereceiverbridge.version>

<dependency>
    <groupId>org.openphc.cce</groupId>
    <artifactId>ccereceiverbridge-omod</artifactId>
    <version>${ccereceiverbridge.version}</version>
</dependency>
```

### Auto-Install via Admin UI

Alternatively, upload `ccereceiverbridge-1.0.0.omod` via **Administration → Manage Modules → Add Module**.

### Configure

Via OpenMRS **Administration → Advanced Settings**:

```
ccereceiverbridge.enabled = true
ccereceiverbridge.identifier.type.name = OpenMRS ID
ccereceiverbridge.visit.type.name = Facility Visit
```

### Docker Network

Unlike the standalone adaptor, **no Docker networking changes needed** — the module runs inside the OpenMRS container. The upstream system just needs to reach the OpenMRS container's HTTP port.

### Health Check

The module is healthy if OpenMRS is healthy:
```
GET /openmrs/ws/rest/v1/session   → 200 OK (OpenMRS running + module loaded)
```

---

## 14. Decision Matrix: Standalone vs. Bridge Module

| Criterion | Standalone Adaptor | Bridge Module | Winner |
|---|---|---|---|
| **Deployment complexity** | Extra container, Docker networking, port management | Single omod JAR inside existing container | 🏆 Bridge |
| **Latency** | 13 HTTP roundtrips per resource (~100-500ms) | 0 HTTP calls, in-JVM (~1-5ms) | 🏆 Bridge |
| **Memory footprint** | ~200MB (Spring Boot + HAPI) | ~20MB (shares OpenMRS JVM) | 🏆 Bridge |
| **Authentication** | Custom dual-auth (inbound + outbound) | OpenMRS native auth | 🏆 Bridge |
| **Monitoring** | Rich (Prometheus, Micrometer, custom metrics) | OpenMRS logging only | 🏆 Standalone |
| **Independent scaling** | Can scale horizontally, separate from OpenMRS | Shares OpenMRS resources | 🏆 Standalone |
| **Failure isolation** | Crash doesn't affect OpenMRS | Module exception could impact OpenMRS | 🏆 Standalone |
| **Development speed** | Spring Boot = fast iteration, rich testing | OpenMRS SDK = slower cycle, complex testing | 🏆 Standalone |
| **Testability** | WireMock, MockMvc, fast unit tests | OpenMRS test framework (heavy) | 🏆 Standalone |
| **Upgrade safety** | Independent release cycle | Coupled to OpenMRS + FHIR2 module versions | 🏆 Standalone |
| **Code complexity** | ~3,900 lines | ~2,200 lines (-44%) | 🏆 Bridge |
| **Operational simplicity** | 2 containers to manage | 1 container | 🏆 Bridge |
| **Network resilience** | Subject to inter-container network failures | No network calls | 🏆 Bridge |
| **Transaction control** | No cross-service transactions | Full Hibernate transaction support | 🏆 Bridge |

**Score: Bridge 9 — Standalone 5**

---

## 15. Recommendation

### For Production at Scale: Keep the Standalone Adaptor

If the deployment serves multiple OpenMRS instances, needs rich observability (Prometheus dashboards, distributed tracing), or needs to scale the receiver independently of OpenMRS, the standalone Spring Boot adaptor is the better choice despite the operational overhead.

### For Single-Site / Simplified Deployments: Build the Bridge Module

If the deployment is a single OpenMRS O3 instance (typical for country-level or district-level implementations), the bridge module eliminates an entire service from the deployment topology:

```
Before: Upstream → Receiver Adaptor (8083) → OpenMRS (8080)   [2 containers]
After:  Upstream → OpenMRS (8080) /ccereceiver/fhir            [1 container]
```

### Hybrid Approach (Recommended)

Keep both as options. The enrichment logic (ConceptResolver, ReferenceResolver, PatientIdentifierEnricher, etc.) can be extracted into a **shared library** used by both:

```
openmrs-cce-enrichment-core/          ← Shared: pure JSON enrichment logic
├── ConceptResolverCore.java           (abstract, API-agnostic)
├── RequiredFieldEnricher.java         (concrete, pure logic)
├── ElementIdEnricher.java             (concrete, pure logic)
├── LuhnMod30.java                     (utility)
└── ...

openmrs-cce-receiver-adaptor/         ← Standalone Spring Boot
├── Uses enrichment-core + HTTP-based resolvers

openmrs-module-cce-receiver-bridge/   ← OpenMRS omod
├── Uses enrichment-core + OpenMRS Java API resolvers
```

This avoids duplicating the ~400 lines of pure logic and ensures both implementations stay in sync.

---

## Appendix A: OpenMRS Service API Quick Reference

| Current HTTP Call | Replacement Java API |
|---|---|
| `GET /concept?source=LOINC&code=85354-9` | `Context.getConceptService().getConceptByMapping("85354-9", "LOINC")` |
| `GET /Patient?identifier=NID-123` | `Context.getPatientService().getPatients(null, "NID-123", null, false)` |
| `GET /Practitioner?identifier=X` | `Context.getProviderService().getProvidersByPerson(person)` |
| `GET /Location?name=X` | `Context.getLocationService().getLocations(name)` |
| `GET /encountertype?v=default` | `Context.getEncounterService().getAllEncounterTypes()` |
| `GET /visit?patient=X&includeInactive=false` | `Context.getVisitService().getActiveVisitsByPatient(patient)` |
| `POST /visit` | `Context.getVisitService().saveVisit(visit)` |
| `GET /location?tag=Login+Location` | `Context.getLocationService().getLocationsByTag(tag)` |
| `GET /patientidentifiertype` | `Context.getPatientService().getAllPatientIdentifierTypes()` |
| `POST /patientidentifiertype` | `Context.getPatientService().savePatientIdentifierType(type)` |
| `GET /visittype?v=default` | `Context.getVisitService().getAllVisitTypes()` |
| `GET /idgen/identifiersource` | `Context.getService(IdentifierSourceService.class).getAllIdentifierSources()` |
| `POST /ws/fhir2/R4/Patient` | `fhirPatientService.create(patient)` (FHIR2 internal) |
| `PUT /ws/fhir2/R4/Patient/{uuid}` | `fhirPatientService.update(uuid, patient)` (FHIR2 internal) |
| `POST /order` (TestOrder) | `Context.getOrderService().saveOrder(testOrder, null)` |
| `POST /order` (DrugOrder) | `Context.getOrderService().saveOrder(drugOrder, null)` |

## Appendix B: Maven Dependencies

```xml
<!-- Parent POM -->
<parent>
    <groupId>org.openmrs.maven.parents</groupId>
    <artifactId>maven-parent-openmrs-module</artifactId>
    <version>1.1.1</version>
</parent>

<!-- API module -->
<dependencies>
    <dependency>
        <groupId>org.openmrs.api</groupId>
        <artifactId>openmrs-api</artifactId>
        <type>jar</type>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.openmrs.module</groupId>
        <artifactId>fhir2-api</artifactId>
        <version>${fhir2.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>ca.uhn.hapi.fhir</groupId>
        <artifactId>hapi-fhir-structures-r4</artifactId>
        <scope>provided</scope>  <!-- Provided by FHIR2 module -->
    </dependency>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <scope>provided</scope>  <!-- Provided by OpenMRS -->
    </dependency>
</dependencies>

<!-- OMOD module -->
<dependencies>
    <dependency>
        <groupId>org.openmrs.web</groupId>
        <artifactId>openmrs-web</artifactId>
        <type>jar</type>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>org.openmrs.module</groupId>
        <artifactId>webservices.rest-omod</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

## Appendix C: Side-by-Side File Comparison

| Standalone Adaptor File | Bridge Module Equivalent | Status |
|---|---|---|
| `CceReceiverAdaptorApplication.java` | `CceReceiverBridgeActivator.java` | Rewrite |
| `config/OpenMrsProperties.java` | `CceReceiverBridgeConfig.java` | Rewrite |
| `config/DiscoveredConfig.java` | (eliminated — use services directly) | Eliminated |
| `config/OpenMrsConfigDiscovery.java` | (inline in Activator + enrichers) | Eliminated |
| `config/RestClientConfig.java` | (eliminated) | Eliminated |
| `config/SecurityConfig.java` | (eliminated — OpenMRS native) | Eliminated |
| `config/OAuth2TokenProvider.java` | (eliminated) | Eliminated |
| `config/RetryConfig.java` | (eliminated) | Eliminated |
| `config/FhirConfig.java` | (FhirContext from FHIR2 module) | Eliminated |
| `controller/InboundResourceController.java` | `web/CceReceiverResource.java` | Rewrite |
| `controller/DiagnosticsController.java` | (eliminated) | Eliminated |
| `model/ResourceEntry.java` | `model/ResourceEntry.java` | Copy |
| `model/RoutingResult.java` | `model/RoutingResult.java` | Copy |
| `model/ProcessingResponse.java` | `model/ProcessingResponse.java` | Copy |
| `service/InboundProcessingService.java` | `processor/InboundProcessor.java` | Adapt |
| `service/BundleSplitter.java` | `splitter/BundleSplitter.java` | Copy |
| `service/ResourceRouter.java` | `router/ResourceRouter.java` | Adapt |
| `service/OpenMrsFhirClient.java` | `saver/FhirResourceSaver.java` | Rewrite |
| `service/OpenMrsRestClient.java` | `saver/OrderSaver.java` | Rewrite |
| `service/ConceptResolver.java` | `enricher/ConceptResolver.java` | Rewrite |
| `service/FhirReferenceResolver.java` | `enricher/ReferenceResolver.java` + `enricher/EncounterTypeResolver.java` | Rewrite (split) |
| `service/PatientIdentifierEnricher.java` | `enricher/PatientIdentifierEnricher.java` | Adapt |
| `service/RequiredFieldEnricher.java` | `enricher/RequiredFieldEnricher.java` | Copy |
| `service/FhirElementIdEnricher.java` | `enricher/ElementIdEnricher.java` | Copy |
| `service/VisitManager.java` | `enricher/VisitLinker.java` | Rewrite |
| `service/SourceIdMappingStore.java` | (eliminated — use PatientService) | Eliminated |
| `fhir/CapabilityStatementCache.java` | (eliminated) | Eliminated |
| `fhir/FhirResourceParser.java` | (use FHIR2's FhirContext directly) | Eliminated |
| `transformer/FhirToRestTransformer.java` | (replaced by OrderSaver) | Replaced |
| `exception/FhirParsingException.java` | (use OpenMRS APIException) | Eliminated |
| `exception/ResourceTransformException.java` | (use OpenMRS APIException) | Eliminated |
| `exception/OpenMrsClientException.java` | (eliminated — no HTTP client) | Eliminated |
| `exception/GlobalExceptionHandler.java` | (eliminated — OpenMRS REST handles errors) | Eliminated |
| **32 files** | **~14 files** | **-56%** |
