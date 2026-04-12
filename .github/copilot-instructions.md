# CCE Receiver Adaptor — Copilot Instructions

> **Purpose:** This document captures every design decision, enrichment rule, resolver strategy, and OpenMRS-specific workaround implemented in the CCE Receiver Adaptor. Use it as a blueprint to rebuild the service from scratch in a new git repository.

---

## 1. Project Overview

**What it does:** A Spring Boot middleware that receives FHIR R4 payloads (Bundles or standalone resources) from upstream systems (RHIE / SPICE / eBuzima) and routes them into an OpenMRS instance via its FHIR R4 or REST v1 endpoints.

**Why it exists:** OpenMRS's FHIR module has gaps — missing required fields, missing identifier types, concept codes in foreign terminologies, non-UUID references, sub-elements without database IDs, and resources not exposed via FHIR at all. This adaptor bridges every gap automatically.

### Tech Stack

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.4.1 |
| HAPI FHIR | 7.4.0 (R4) |
| Gradle | Wrapper (Groovy DSL) |
| Spring Security + OAuth2 Resource Server | Inbound API auth (Basic + JWT) |
| Spring Retry + Spring Aspects | For resilient outbound calls |
| Micrometer + Prometheus | Observability |
| WireMock | 3.9.2 (test only) |

### Dependencies (build.gradle)

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.4.1'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'org.openphc.cce'
version = '1.0.0-SNAPSHOT'

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

ext {
    hapiFhirVersion = '7.4.0'
    wiremockVersion = '3.9.2'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
    implementation 'org.springframework.retry:spring-retry'
    implementation 'org.springframework:spring-aspects'
    implementation "ca.uhn.hapi.fhir:hapi-fhir-base:${hapiFhirVersion}"
    implementation "ca.uhn.hapi.fhir:hapi-fhir-structures-r4:${hapiFhirVersion}"
    implementation 'io.micrometer:micrometer-registry-prometheus'
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation "org.wiremock:wiremock-standalone:${wiremockVersion}"
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

---

## 2. Package Structure

```
org.openphc.cce.receiver
├── CceReceiverAdaptorApplication.java   # @SpringBootApplication + @EnableRetry + @EnableConfigurationProperties
├── config/
│   ├── OpenMrsProperties.java           # @ConfigurationProperties(prefix="openmrs") — immutable YAML binding
│   ├── DiscoveredConfig.java            # @Component — mutable runtime config populated by auto-discovery
│   ├── OpenMrsConfigDiscovery.java      # @Component — startup auto-discovery from OpenMRS REST API
│   ├── RestClientConfig.java            # @Configuration — two RestClient beans (FHIR + REST) with dual auth
│   ├── SecurityConfig.java             # @EnableWebSecurity — inbound API auth (disabled/Basic/JWT)
│   ├── OAuth2TokenProvider.java         # @Component — outbound OAuth2 Client Credentials token cache
│   ├── RetryConfig.java                # @Configuration — Spring Retry configuration
│   └── FhirConfig.java                 # @Configuration — singleton FhirContext.forR4()
├── controller/
│   ├── InboundResourceController.java   # POST /api/v1/fhir — main entry point
│   └── DiagnosticsController.java       # GET /api/v1/capabilities, /api/v1/routing-table
├── model/
│   ├── ResourceEntry.java               # record(resourceType, resourceJson, method, fullUrl)
│   ├── RoutingResult.java               # record(resourceType, resourceId, route, status, httpStatus, responseBody, errorMessage)
│   └── ProcessingResponse.java          # record(timestamp, totalEntries, succeeded, failed, results)
├── service/
│   ├── InboundProcessingService.java    # Orchestrator: split → route → aggregate
│   ├── BundleSplitter.java             # Decomposes Bundle / wraps standalone resource
│   ├── ResourceRouter.java             # Dependency ordering + FHIR-vs-REST routing + source-ID mapping
│   ├── OpenMrsFhirClient.java          # FHIR R4 client with enrichment pipeline
│   ├── OpenMrsRestClient.java          # REST v1 fallback client
│   ├── CapabilityStatementCache.java   # Caches /metadata for routing decisions (in fhir/ package)
│   ├── SourceIdMappingStore.java       # In-memory sourceId→openMrsUuid mapping
│   ├── ConceptResolver.java            # LOINC/SNOMED/CIEL → OpenMRS concept UUID
│   ├── FhirReferenceResolver.java      # Resolves non-UUID references + concept codes + encounter types
│   ├── PatientIdentifierEnricher.java  # LuhnMod30 ID generation + source identifier promotion
│   ├── RequiredFieldEnricher.java      # Auto-fills missing required fields
│   ├── FhirElementIdEnricher.java      # Adds UUID `id` to array sub-elements
│   └── VisitManager.java              # Auto-creates Visits for Encounters (O3 UI requirement)
├── fhir/
│   ├── FhirResourceParser.java         # HAPI FHIR parse/encode wrapper
│   └── CapabilityStatementCache.java   # (see above — lives in fhir/ package)
├── transformer/
│   └── FhirToRestTransformer.java      # FHIR → OpenMRS REST payload conversion
└── exception/
    ├── FhirParsingException.java       # 422 — invalid FHIR JSON
    ├── ResourceTransformException.java # 422 — FHIR→REST transform failure (has resourceType field)
    ├── OpenMrsClientException.java     # 502 — OpenMRS communication failure (has statusCode field)
    └── GlobalExceptionHandler.java     # @ControllerAdvice — maps exceptions to JSON error responses
```

---

## 3. Configuration

### application.yml

```yaml
server:
  port: 8083

# Inbound API Security (disabled by default; enable for production)
cce:
  security:
    enabled: false                 # Set to true to require auth on adaptor endpoints
    username: cce-client
    password: changeme
    oauth2:
      issuer-uri:                  # Leave blank to disable inbound OAuth2 JWT
      required-scope:

# OpenMRS Configuration
openmrs:
  fhir:
    base-url: http://localhost:9096/openmrs/ws/fhir2/R4
    timeout: 10000
  rest:
    base-url: http://localhost:9096/openmrs/ws/rest/v1
    timeout: 10000
  auth:
    type: basic                    # "basic" or "oauth2"
    username: admin
    password: Admin123
    oauth2:                        # Used when type=oauth2 (Client Credentials grant)
      token-url:                   # e.g. http://keycloak:8180/realms/openmrs/protocol/openid-connect/token
      client-id:
      client-secret:
      scope:
  capability-refresh-ms: 21600000  # 6 hours

management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  endpoint.health:
    show-details: when-authorized
    probes.enabled: true
  health:
    livenessState.enabled: true
    readinessState.enabled: true
  metrics.tags.application: cce-receiver-adaptor

logging:
  level:
    org.openphc.cce: DEBUG
  pattern.console: "%d{ISO8601} [%thread] %-5level %logger{36} [%X{correlationId:-}] [%X{source:-}] [%X{resourceType:-}] - %msg%n"
```

### OpenMrsProperties (immutable, YAML-bound)

```java
@ConfigurationProperties(prefix = "openmrs")
public record OpenMrsProperties(
    FhirProperties fhir,    // baseUrl, timeout
    RestProperties rest,    // baseUrl, timeout
    AuthProperties auth,    // type, username, password, oauth2 (tokenUrl, clientId, clientSecret, scope)
    IdentifierProperties identifier  // locationUuid, idgenSourceUuid, typeName (all optional fallbacks)
) {}
```

`AuthProperties` fields:
- `type` — `"basic"` (default) or `"oauth2"`; selects outbound auth mechanism
- `username` / `password` — used when `type=basic`
- `oauth2.tokenUrl` — IdP token endpoint (used when `type=oauth2`)
- `oauth2.clientId` / `oauth2.clientSecret` — OAuth2 Client Credentials
- `oauth2.scope` — optional scope to request
- `effectiveType()` — helper returning normalized type, defaulting to `"basic"`

### DiscoveredConfig (mutable, runtime-populated)

Populated by `OpenMrsConfigDiscovery` at startup. Fields:
- `locationUuid` — Location for patient identifier extension
- `identifierTypeName` — e.g. "OpenMRS ID"
- `identifierTypeUuid` — UUID of the primary identifier type
- `idgenSourceUuid` — UUID of idgen source (for server-side generation, currently unused)
- `sourceIdentifierTypes` — List of source ID type names discovered from OpenMRS (e.g. ["NID", "UPI"])
- `identifierTypeUuids` — Map of type name → UUID for all non-primary identifier types

Thread-safe: `volatile` fields, `synchronized addSourceIdentifierType()` method.

### RestClientConfig

Creates two named `RestClient` beans:
1. `@Qualifier("fhirRestClient")` → targets `openmrs.fhir.base-url` with `application/fhir+json` content type
2. `@Qualifier("openmrsRestClient")` → targets `openmrs.rest.base-url` with `application/json` content type

Authentication mode is controlled by `openmrs.auth.type`:
- **`basic`** (default) — sets a static `Authorization: Basic ...` header
- **`oauth2`** — adds a `ClientHttpRequestInterceptor` that calls `OAuth2TokenProvider.getAuthorizationHeader()` per request, injecting a fresh `Bearer` token (auto-refreshed before expiry)

### OAuth2TokenProvider

`@Component` that implements the OAuth2 Client Credentials grant (RFC 6749 §4.4) for outbound calls:
- Fetches access tokens from the configured IdP token endpoint (`openmrs.auth.oauth2.token-url`)
- Caches tokens in memory; refreshes 30 seconds before expiry
- Thread-safe via `synchronized` on `getAccessToken()`
- `isOAuth2Configured()` — returns true if token-url and client-id are set
- `getAuthorizationHeader()` — returns `"Bearer <token>"`

### SecurityConfig

`@EnableWebSecurity` configuration for the adaptor's **inbound** API. Three modes:

| `cce.security.enabled` | Behavior |
|---|---|
| `false` (default) | All endpoints open — no authentication required |
| `true` | Basic Auth required; optionally also accepts OAuth2 JWT Bearer |

When `enabled=true`:
- **Basic Auth** — always active. In-memory user from `cce.security.username`/`password`
- **OAuth2 JWT** — additionally active when `cce.security.oauth2.issuer-uri` is set. Validates JWT from Keycloak/Azure AD/any OIDC IdP
- Both mechanisms work simultaneously — caller can use either

**Always-public endpoints** (regardless of `enabled`):
- `/actuator/health`, `/actuator/health/**`, `/actuator/info`

**Protected endpoints** (when `enabled=true`):
- `POST /api/v1/fhir`, `GET /api/v1/capabilities`, `GET /api/v1/routing-table`
- `/actuator/prometheus`, `/actuator/metrics`

Stateless (no sessions): `SessionCreationPolicy.STATELESS`, CSRF disabled.

### FhirConfig

Singleton `FhirContext.forR4()` bean — expensive to create, shared globally.

---

## 4. Processing Pipeline

### End-to-End Flow

```
HTTP POST /api/v1/fhir (FHIR JSON body)
  │
  ├── Headers: X-Correlation-ID (optional), X-Source-System (optional)
  │
  └── InboundResourceController
        │
        └── InboundProcessingService.process()
              │
              ├── [1] BundleSplitter.split(json) → List<ResourceEntry>
              │
              ├── [2] ResourceRouter.route(entries) → List<RoutingResult>
              │       │
              │       ├── Sort by DEPENDENCY_ORDER
              │       ├── For each entry:
              │       │   ├── Resolve cross-references (urn:uuid → server UUID)
              │       │   ├── Source-ID mapping (create vs update detection)
              │       │   ├── CapabilityStatement check → FHIR or REST
              │       │   ├── If FHIR:  OpenMrsFhirClient.send() → enrichment pipeline
              │       │   └── If REST:  OpenMrsRestClient.send() → FhirToRestTransformer
              │       │
              │       └── Store UUID mapping for cross-references
              │
              └── [3] ProcessingResponse.from(results)
```

### Response Format

```json
{
  "timestamp": "2025-01-15T10:30:00.000Z",
  "totalEntries": 3,
  "succeeded": 3,
  "failed": 0,
  "results": [
    {
      "resourceType": "Patient",
      "resourceId": "abc-123-uuid",
      "route": "fhir",
      "status": "created",
      "httpStatus": 201,
      "responseBody": "...",
      "errorMessage": null
    }
  ]
}
```

HTTP status: `202 Accepted` if all succeed, `207 Multi-Status` if any fail.

---

## 5. BundleSplitter

**Purpose:** Normalizes incoming payloads into a uniform `List<ResourceEntry>`.

### Rules

1. **Bundle payload:** Extracts each `entry[].resource` with its `request.method` and `fullUrl`.
2. **Standalone resource:** Wraps in a single `ResourceEntry`.
3. **Method detection for standalone:** If resource has an `id` matching UUID regex `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-...` → `PUT`, otherwise `POST`.
4. **Non-UUID IDs** (e.g., UPI "251119-0001-4106") are treated as `POST` (create) — the source-ID mapping in ResourceRouter handles the create-vs-update decision.

UUID regex: `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`

---

## 6. ResourceRouter

### Dependency Ordering

Resources are sorted before processing to ensure dependencies are created first:

| Priority | Resource Type |
|---|---|
| 1 | Patient |
| 2 | Practitioner |
| 3 | Location |
| 4 | Medication |
| 5 | Encounter |
| 10 | Everything else (default) |

### Routing Decision

```
if CapabilityStatementCache.canFhir(resourceType, interactionCode) → FHIR path
else → REST fallback path
```

Special case: `ORDER_TYPES = {"ServiceRequest", "MedicationRequest"}` always get REST fallback because OpenMRS FHIR module doesn't support orders.

### Source-ID Mapping (Create vs Update)

**How it works:**
1. Extract `id` field from resource JSON
2. Look up in `SourceIdMappingStore` by `(resourceType, sourceId)`
3. If mapping exists → replace `id` with OpenMRS UUID, set method to `PUT` (update)
4. If no mapping → remove `id` field, set method to `POST` (create)
5. On successful create → store mapping `(resourceType, sourceId) → openMrsUuid`

**Cross-reference resolution:**
- Bundle entries reference each other via `fullUrl` (urn:uuid or ResourceType/sourceId)
- After each successful create, the UUID mapping is stored in a local `HashMap<String, String>` for the current request
- Subsequent entries' JSON is string-replaced: `"Patient/source-id-123"` → `"Patient/openmrs-uuid-456"`

**Current limitation:** `SourceIdMappingStore` is in-memory (`ConcurrentHashMap`), lost on restart.

**Recommended approach for production:** Source system should own the mapping. The adaptor returns `resourceId` (OpenMRS UUID) in every `results[]` entry. The source system stores `sourceId → openMrsUuid` and sends the OpenMRS UUID on subsequent updates.

### DELETE Handling

- If source ID has a mapping → resolve to OpenMRS UUID → DELETE
- If no mapping → return 404 failure (cannot delete unknown resource)

---

## 7. FHIR Enrichment Pipeline

When a resource goes through the FHIR path (`OpenMrsFhirClient.send()`), it passes through **four enrichers** plus the **VisitManager** in this exact order:

```
[1] FhirReferenceResolver.resolve()
[2] RequiredFieldEnricher.enrich()        (in REST path too)
[3] PatientIdentifierEnricher.enrich()    (Patient only)
[4] FhirElementIdEnricher.enrich()
[5] VisitManager.ensureVisitLinked()      (Encounter only)
```

The REST path (`OpenMrsRestClient.send()`) only runs:
```
[1] FhirReferenceResolver.resolve()
[2] RequiredFieldEnricher.enrich()
```
...then `FhirToRestTransformer.transform()` converts FHIR → REST payload.

---

## 8. FhirReferenceResolver

**File:** `FhirReferenceResolver.java` (~544 lines — the largest service class)

### 8.1 Reference Resolution

**Problem:** Upstream sends references like `"reference": "Patient/251119-0001-4106"` (source identifier) but OpenMRS needs `"reference": "Patient/abc-123-uuid"`.

**Solution:** Walk the JSON tree, find all `"reference"` fields. For each:
1. Parse `ResourceType/identifier`
2. If identifier is already a UUID → skip
3. Search OpenMRS FHIR: `GET /{ResourceType}?identifier={identifier}`
4. Extract UUID from first match → replace reference

**Cache:** `ConcurrentHashMap<String, String>` with `NOT_FOUND` sentinel to avoid re-querying missing references.

### 8.2 Concept Code Resolution

**Problem:** Upstream sends LOINC/SNOMED codes in `CodeableConcept` fields. OpenMRS needs the concept's internal UUID as a `coding` entry.

**Solution:** For each codeable concept field, delegate to `ConceptResolver`:
1. Check for existing OpenMRS-native coding (system containing "openmrs")
2. If code looks like a UUID → use directly
3. Otherwise → call OpenMRS REST API: `GET /concept?source={source}&code={code}`
4. Add OpenMRS-native coding entry at **position 0** in the coding array

**Fields processed (CODEABLE_CONCEPT_FIELDS):**
- `code`, `medicationCodeableConcept`, `vaccineCode`, `valueCodeableConcept`
- `bodySite`, `method`, `clinicalStatus`, `verificationStatus`, `severity`
- `serviceType`, `reasonCode`

**Array fields processed (CONCEPT_BEARING_ARRAYS):**
- `component` — processes `component[].code` (for Observation)

### 8.3 Encounter Type Resolution

**Problem:** Upstream sends `type[].text = "Visit Note"` but OpenMRS encodes encounter types as `coding[].code = UUID`.

**Solution:** For Encounter resources, resolve `type[].text` or `coding[].display`:
1. Query `GET /encountertype?v=default`
2. Match strategy: exact match → prefix match → contains match (all case-insensitive)
3. Set `coding[0].code` to the matched encounter type UUID

**Cache:** `ConcurrentHashMap<String, String>` for encounter types (persists for JVM lifetime).

### 8.4 ConceptResolver Details

**System-to-source mapping** (13 entries):

| FHIR `system` URI | OpenMRS Source |
|---|---|
| `http://loinc.org` | LOINC |
| `http://snomed.info/sct` | SNOMED CT |
| `urn:oid:2.16.840.1.113883.6.96` | SNOMED CT |
| `http://hl7.org/fhir/sid/icd-10` | ICD-10-WHO |
| `http://hl7.org/fhir/sid/icd-10-cm` | ICD-10-WHO |
| `http://www.nlm.nih.gov/research/umls/rxnorm` | RxNORM |
| `http://hl7.org/fhir/sid/cvx` | CVX |
| `urn:ietf:rfc:3986` | CIEL |
| `http://ciel.org` | CIEL |
| `https://openconceptlab.org/orgs/CIEL/sources/CIEL` | CIEL |
| `http://fhir.openmrs.org` | (native — use code directly) |
| `http://openmrs.org` | (native — use code directly) |
| `https://openconceptlab.org/orgs/Medtronic-LABS/sources/SPICE` | SPICE |

**Resolution cascade:**
1. If coding has OpenMRS-native system → return code as-is
2. If code matches UUID pattern → return as-is
3. Map system → OpenMRS source name → call `GET /concept?source={source}&code={code}`
4. If not found → throw `ResourceTransformException`

---

## 9. PatientIdentifierEnricher

**File:** `PatientIdentifierEnricher.java` (~491 lines)

### 9.1 Problems Solved

1. **Missing preferred identifier:** OpenMRS requires every Patient to have a `use: "official"` identifier of type "OpenMRS ID" that passes Luhn Mod 30 validation. Without it: `'Patient#null' failed to validate`.
2. **Source identifiers silently dropped:** OpenMRS maps FHIR identifiers to `patient_identifier` rows via `identifier.type.text` → `patient_identifier_type.name`. If an identifier only has `system` (no `type.text`), OpenMRS silently drops it.

### 9.2 OpenMRS ID Injection

1. Check if Patient already has an identifier with `use:"official"` and `type.text` matching the configured identifier type name (usually "OpenMRS ID")
2. If yes → skip
3. If no:
   - Generate unique base using `AtomicLong SEQUENCE` (seeded from `System.currentTimeMillis() % 100000000`)
   - Encode in base-30 using `VALID_CHARS = "0123456789ACDEFGHJKLMNPRTUVWXY"` (excludes B, I, O, Q, S, Z)
   - Compute Luhn Mod 30 check digit and append
   - Create identifier FHIR node with:
     - `id`: random UUID (for OpenMRS DB)
     - `use`: "official"
     - `type.text`: discovered identifier type name
     - `type.coding[0].code`: discovered identifier type UUID
     - `value`: generated ID
     - `extension[0]`: OpenMRS location extension (`http://fhir.openmrs.org/ext/patient/identifier#location`)
   - Demote any existing `use:"official"` identifiers to `use:"secondary"`

### 9.3 Luhn Mod 30 Algorithm

```
Character set: 0123456789ACDEFGHJKLMNPRTUVWXY (30 chars)
Excluded: B, I, O, Q, S, Z (look-alike characters)

Algorithm (same as OpenMRS LuhnMod30IdentifierValidator):
1. Process identifier chars from right to left
2. Alternate factor between 2 and 1
3. For each char: codePoint = index in VALID_CHARS
4. addend = factor × codePoint
5. addend = (addend / 30) + (addend % 30)
6. sum += addend
7. checkDigit = VALID_CHARS[(30 - (sum % 30)) % 30]
```

### 9.4 Source Identifier Promotion

For each identifier in the array that has a `system` but no `type.text`:
1. Match system URI against discovered source identifier types (from `DiscoveredConfig.sourceIdentifierTypes`)
   - First: last path segment exact match (case-insensitive)
   - Then: URI contains type name (case-insensitive)
2. If no match and `OpenMrsConfigDiscovery` is available → **auto-create** the identifier type in OpenMRS:
   - Derive type name from system URI (last path segment)
   - POST to `/patientidentifiertype` with `{name, description, required:false, uniquenessBehavior:"UNIQUE"}`
   - Register in `DiscoveredConfig` immediately (no restart needed)
3. Set `type.text` to matched/created type name
4. Set `type.coding[0].code` to the type UUID
5. **Remove `system` field** — OpenMRS maps by `type.coding`, bare system values like "NID" cause O3 to silently drop the identifier
6. Set `use` to "secondary" if not already set
7. Add location extension if missing

---

## 10. RequiredFieldEnricher

**Purpose:** OpenMRS FHIR module rejects resources missing certain required fields that FHIR R4 considers optional.

### Default Value Types

| Type | Behavior |
|---|---|
| `DATETIME` | Inserts current UTC ISO-8601 datetime |
| `PERIOD_START` | Inserts `{"start": "<current UTC>"}` |
| `VALUE` | Inserts a literal string value |

### Resource-Specific Defaults

| Resource Type | Field | Default Type | Value |
|---|---|---|---|
| **Observation** | `effectiveDateTime` | DATETIME | current UTC |
| **Observation** | `status` | VALUE | `"final"` |
| **Encounter** | `period` | PERIOD_START | current UTC |
| **Encounter** | `status` | VALUE | `"finished"` |
| **Condition** | `recordedDate` | DATETIME | current UTC |
| **Condition** | `clinicalStatus` | VALUE | `{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/condition-clinical","code":"active"}]}` |
| **AllergyIntolerance** | `recordedDate` | DATETIME | current UTC |
| **AllergyIntolerance** | `type` | VALUE | `"allergy"` |
| **Immunization** | `occurrenceDateTime` | DATETIME | current UTC |
| **Immunization** | `status` | VALUE | `"completed"` |
| **DiagnosticReport** | `issued` | DATETIME | current UTC |
| **DiagnosticReport** | `status` | VALUE | `"final"` |
| **ServiceRequest** | `authoredOn` | DATETIME | current UTC |
| **ServiceRequest** | `status` | VALUE | `"active"` |
| **ServiceRequest** | `intent` | VALUE | `"order"` |
| **MedicationRequest** | `authoredOn` | DATETIME | current UTC |
| **MedicationRequest** | `status` | VALUE | `"active"` |
| **MedicationRequest** | `intent` | VALUE | `"order"` |
| **Task** | `authoredOn` | DATETIME | current UTC |
| **Task** | `status` | VALUE | `"requested"` |
| **Task** | `intent` | VALUE | `"order"` |
| **Procedure** | `performedDateTime` | DATETIME | current UTC |
| **Procedure** | `status` | VALUE | `"completed"` |
| **MedicationDispense** | `whenHandedOver` | DATETIME | current UTC |
| **MedicationDispense** | `status` | VALUE | `"completed"` |
| **MedicationAdministration** | `effectiveDateTime` | DATETIME | current UTC |
| **MedicationAdministration** | `status` | VALUE | `"completed"` |
| **Consent** | `dateTime` | DATETIME | current UTC |
| **Consent** | `status` | VALUE | `"active"` |

Fields are ONLY set if they are **missing** from the incoming JSON. Existing values are never overwritten.

---

## 11. FhirElementIdEnricher

**Problem:** OpenMRS FHIR module maps many array sub-elements (identifier, name, address, etc.) to Hibernate entities that require a `uuid` column. If the JSON element lacks an `id` field, Hibernate throws: `Column 'uuid' cannot be null`.

**Solution:** Recursively walk the JSON tree. For any array whose name is in `ARRAY_FIELDS_NEEDING_IDS`, and for any object in that array that lacks an `id` field, inject `"id": "<random-UUID>"`.

### Array Fields Needing IDs

```
identifier, name, address, telecom, contact, communication,
participant, location, type, diagnosis, component, performer,
referenceRange, reaction, qualification, coding, category,
interpretation, note
```

**Recursion depth cap:** 10 (prevents stack overflow on deeply nested resources).

**Applies to ALL resource types** (runs on every FHIR-path resource, not just Patient).

---

## 11.5 VisitManager

**File:** `VisitManager.java`

### Problem

In OpenMRS O3, encounters only appear in the "Visits → All encounters" UI when they are linked to a Visit via the FHIR `partOf` reference. When the adaptor creates an Encounter without a Visit association, it becomes invisible in the O3 UI — even though the Encounter and its attached observations exist in the database.

### Solution

Before an Encounter is POSTed to the FHIR endpoint, `VisitManager.ensureVisitLinked()`:

1. Checks if the Encounter already has a `partOf` reference → skips if yes
2. Detects Visit-type encounters (no `type` array) → skips them
3. Extracts the patient UUID from `subject.reference`
4. Queries OpenMRS REST API for an active (open) Visit for the patient: `GET /visit?patient={uuid}&includeInactive=false`
5. If no active Visit exists, creates one via `POST /visit` with:
   - `patient`: the patient UUID
   - `visitType`: discovered visit type UUID (default: "Facility Visit")
   - `startDatetime`: from the encounter's `period.start` (or current time)
   - `location`: discovered location UUID
6. Sets `partOf.reference` to `Encounter/<visitUuid>` on the encounter JSON

### Visit Type Discovery

At startup, `OpenMrsConfigDiscovery` queries `GET /visittype?v=default` and selects:
1. "Facility Visit" (preferred, most common in O3)
2. First available visit type (fallback)

The UUID is stored in `DiscoveredConfig.visitTypeUuid`.

### OpenMRS Data Model Context

In OpenMRS, a Visit is a container for Encounters. In the FHIR representation:
- Visits are modeled as FHIR `Encounter` resources with visit-related types and no `partOf`
- Clinical Encounters have specific types (e.g., "Visit Note") and link to the Visit via `partOf`

### Per-Request Caching

A `ConcurrentHashMap<String, String>` caches `patientUuid → visitUuid` during a single inbound request (Bundle processing). This prevents duplicate Visit creation when a Bundle contains multiple Encounters for the same patient. The cache is cleared by `InboundProcessingService` after each request.

---

## 12. CapabilityStatementCache

**Purpose:** Determines whether to route a resource through FHIR or REST by parsing OpenMRS's `/metadata` (CapabilityStatement).

### Behavior

1. **Refresh on startup** + every `openmrs.capability-refresh-ms` (default 6 hours)
2. Fetches `GET /metadata` from FHIR endpoint
3. Parses `rest[0].resource[]` to build `Map<resourceType, Set<interactionCode>>`
4. `canFhir(resourceType, interactionCode)` → `true` if the interaction is listed

### Exposed State

- `getSupportedResourceTypes()` → set of all resource type names
- `getCapabilities()` → `Map<String, Set<String>>` (full routing table)
- `getLastRefreshed()` → `Instant` of last successful refresh

---

## 13. OpenMRS Config Discovery

**File:** `OpenMrsConfigDiscovery.java` (~434 lines)

**Trigger:** `@EventListener(ApplicationReadyEvent.class)` — runs after full Spring context startup.

### What Gets Discovered

| Config | API Endpoint | Strategy |
|---|---|---|
| Location UUID | `GET /location?tag=Login+Location` | Preferred names: "Registration Desk" > "Registration" > "Outpatient Clinic" > "Outpatient" > first Login Location > any location |
| Identifier Type UUID | `GET /patientidentifiertype` | Match by name (e.g. "OpenMRS ID") |
| Idgen Source UUID | `GET /idgen/identifiersource` | First source linked to primary identifier type |
| Source Identifier Types | `GET /patientidentifiertype` | All non-retired, non-primary types → these are admin-registered types for source IDs (NID, UPI, etc.) |

### Fallback

If discovery fails (OpenMRS unreachable), falls back to static values from `application.yml` (`openmrs.identifier.*`).

### On-Demand Identifier Type Creation

`ensureIdentifierTypeExists(typeName)`:
1. Check `DiscoveredConfig` cache
2. Re-query OpenMRS (`GET /patientidentifiertype`)
3. If not found → create via `POST /patientidentifiertype` with `{name, description, required:false, uniquenessBehavior:"UNIQUE"}`
4. Register in `DiscoveredConfig.addSourceIdentifierType(name, uuid)` — immediately available without restart

---

## 14. FhirToRestTransformer

**Purpose:** Converts FHIR R4 resources to OpenMRS REST v1 payloads for resources not supported via FHIR.

### ServiceRequest → TestOrder

| FHIR Field | REST Field |
|---|---|
| `code.coding[0].code` | `concept` (UUID) |
| `encounter.reference` | `encounter` (UUID, extracted after `/`) |
| `subject.reference` | `patient` (UUID) |
| `requester.reference` | `orderer` (UUID) |
| `intent` | `action`: always "NEW" |
| `priority` | `urgency`: "stat"/"asap"/"urgent" → "STAT", else "ROUTINE" |
| (hardcoded) | `careSetting`: "OUTPATIENT" |
| (hardcoded) | `type`: "testorder" |

REST endpoint: `POST /order`

### MedicationRequest → DrugOrder

| FHIR Field | REST Field |
|---|---|
| `medicationCodeableConcept.coding[0].code` | `concept` (UUID) |
| `medicationReference.reference` | `drug` (UUID) |
| `encounter.reference` | `encounter` (UUID) |
| `subject.reference` | `patient` (UUID) |
| `requester.reference` | `orderer` (UUID) |
| `dosageInstruction[0].doseAndRate[0].doseQuantity.value` | `dose` |
| `dosageInstruction[0].doseAndRate[0].doseQuantity.unit` | `doseUnits` |
| `dosageInstruction[0].route.coding[0].code` | `route` (UUID) |
| `dosageInstruction[0].timing.code.coding[0].code` | `frequency` (UUID) |
| `dispenseRequest.quantity.value` | `quantity` |
| `dispenseRequest.expectedSupplyDuration.value` | `duration` |
| `dispenseRequest.numberOfRepeatsAllowed` | `numRefills` |
| (hardcoded) | `careSetting`: "OUTPATIENT" |
| (hardcoded) | `type`: "drugorder" |

REST endpoint: `POST /order`

### Generic Fallback

For unsupported types, maps FHIR type to REST endpoint:
- Patient→patient, Encounter→encounter, Observation→obs, Condition→condition
- AllergyIntolerance→allergy, Location→location, Practitioner→provider, Medication→drug
- Default: `resourceType.toLowerCase()`

---

## 15. Retry & Error Handling

### OpenMrsFhirClient & OpenMrsRestClient

```java
@Retryable(
    retryFor = {HttpServerErrorException.class, ResourceAccessException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2.0)
)
```

- **5xx errors:** Retried (3 attempts, exponential backoff 1s → 2s → 4s)
- **4xx errors:** NOT retried — returned as failure immediately
- **Timeouts (ResourceAccessException):** Retried
- **@Recover:** After all retries exhausted → return `RoutingResult.failure` with 502 status

### GlobalExceptionHandler

| Exception | HTTP Status | Error Code |
|---|---|---|
| `FhirParsingException` | 422 | FHIR_PARSING_ERROR |
| `ResourceTransformException` | 422 | TRANSFORM_ERROR |
| `OpenMrsClientException` | 502 | OPENMRS_CLIENT_ERROR |
| `Exception` (generic) | 500 | INTERNAL_ERROR |

Error response format:
```json
{
  "error": {
    "code": "FHIR_PARSING_ERROR",
    "message": "...",
    "timestamp": "2025-01-15T10:30:00Z"
  }
}
```

---

## 16. Observability

### Metrics (Micrometer/Prometheus)

| Metric | Type | Tags |
|---|---|---|
| `cce.receiver.requests.received` | Counter | source |
| `cce.receiver.resources.routed` | Counter | resourceType, route, status |
| `cce.receiver.processing.total` | Timer | — |
| `cce.receiver.fhir.latency` | Timer | resourceType, method |
| `cce.receiver.rest.latency` | Timer | resourceType, method |

### Structured Logging

MDC context set per request:
- `correlationId` — from `X-Correlation-ID` header
- `source` — from `X-Source-System` header
- `resourceType` — set per resource during routing

Log pattern: `%d [%thread] %-5level %logger{36} [%X{correlationId:-}] [%X{source:-}] [%X{resourceType:-}] - %msg%n`

### Endpoints

- `GET /api/v1/capabilities` — CapabilityStatement cache contents
- `GET /api/v1/routing-table` — simplified FHIR vs REST routing table
- `GET /actuator/health` — liveness/readiness probes
- `GET /actuator/prometheus` — Prometheus metrics scrape endpoint

---

## 17. Source-ID Mapping — Design Decision

### Problem

When the source system sends a resource for the first time, it has a source-system ID (e.g., `"id": "encounter-12345"`). OpenMRS assigns its own UUID. On subsequent updates, the adaptor needs to know which OpenMRS UUID corresponds to the source ID.

### Approaches Evaluated

| Approach | Pros | Cons | Recommendation |
|---|---|---|---|
| **In-memory mapping (current)** | Simple, fast | Lost on restart | Development/testing only |
| **H2/DB persistence in adaptor** | Survives restarts | Adds state to middleware, single point of failure | Viable for small deployments |
| **Source system owns mapping** | Adaptor stays stateless, source has full control | Requires source to parse response and store mappings | **Recommended for production** |
| **Search-before-write** | No mapping needed | Unreliable (false positive updates, multiple matches, impossible for Observations, performance overhead) | **Not recommended** |

### Recommended: Source-Owns-Mapping

1. Source system sends resource with its own ID
2. Adaptor creates it in OpenMRS → returns `results[].resourceId` (OpenMRS UUID)
3. Source system stores mapping: `sourceId → openMrsUuid`
4. On update, source sends resource with `"id": "openmrs-uuid"` → adaptor does PUT directly
5. Adaptor remains fully stateless — no persistence needed

---

## 18. OpenMRS-Specific Quirks & Workarounds

### 18.1 Sub-element UUID Requirement
OpenMRS maps FHIR array elements (identifier, name, address, etc.) to Hibernate entities. Each entity row needs a `uuid` column. FHIR doesn't require `id` on sub-elements. **Workaround:** `FhirElementIdEnricher` injects UUID `id` fields.

### 18.2 Patient Identifier Location Extension  
Every `patient_identifier` row in OpenMRS requires a `location_id`. FHIR has no standard for this. **Workaround:** `PatientIdentifierEnricher` adds the OpenMRS-specific extension `http://fhir.openmrs.org/ext/patient/identifier#location` with `valueReference: {reference: "Location/<uuid>"}`.

### 18.3 Identifier Type Mapping by `type.coding` (not `system`)
O3 ignores FHIR `system` for identifier-type matching. It uses `type.coding[0].code` (must be the identifier type UUID) and `type.text` (must match `patient_identifier_type.name`). **Workaround:** `PatientIdentifierEnricher` sets both and **removes** the `system` field.

### 18.4 Encounter Type via `type[].coding[].code`
OpenMRS doesn't accept encounter type by `text` alone. It needs `type[0].coding[0].code` set to the encounter type UUID. **Workaround:** `FhirReferenceResolver` queries `/encountertype` and sets the UUID.

### 18.5 Concept Codes Must Be OpenMRS UUIDs
LOINC/SNOMED codes in `code.coding` are not recognized by OpenMRS unless there's also a coding with the OpenMRS concept UUID. **Workaround:** `ConceptResolver` looks up the concept via REST API and injects an OpenMRS-native coding at position 0.

### 18.6 Orders Not Supported via FHIR
`ServiceRequest` and `MedicationRequest` cannot be created via the OpenMRS FHIR endpoint (the Order model is complex). **Workaround:** `FhirToRestTransformer` converts them to REST `testorder`/`drugorder` payloads → `POST /order`.

### 18.7 Non-UUID References Can't Resolve
OpenMRS FHIR module expects references like `Patient/<uuid>`. Source systems send `Patient/<external-id>`. **Workaround:** `FhirReferenceResolver` searches by identifier and replaces the reference.

### 18.8 LuhnMod30 Validator on Primary Identifier
OpenMRS rejects Patient creation if the preferred identifier doesn't pass the configured validator (usually LuhnMod30). **Workaround:** `PatientIdentifierEnricher` generates identifiers locally using the same algorithm.

### 18.9 Encounters Invisible Without Visit Linkage
O3 only shows encounters in the "Visits → All encounters" tab when they have `partOf` referencing a Visit. An encounter without `partOf` is created in the database but invisible in the O3 UI. **Workaround:** `VisitManager` automatically finds or creates a Facility Visit for the patient and sets `partOf` before the Encounter is POSTed.

---

## 19. Testing Patterns

### Test Dependencies

```groovy
testImplementation 'org.springframework.boot:spring-boot-starter-test'  // JUnit 5, Mockito, AssertJ
testImplementation 'org.springframework.security:spring-security-test'  // MockMvc with security
testImplementation "org.wiremock:wiremock-standalone:${wiremockVersion}"  // HTTP mocking
```

### Test Structure

Tests mirror the main source structure under `src/test/java/org/openphc/cce/receiver/`:

- **Unit tests:** Mock dependencies with Mockito, test each component in isolation
- **WireMock tests:** Stub OpenMRS FHIR/REST endpoints to test enrichers and clients
- **Security tests:** `SecurityConfigTest` tests both `enabled=false` (open) and `enabled=true` (auth required) modes using `@TestPropertySource`
- **Test profile:** `application-test.yml` with `cce.security.enabled: false`

### Key Testing Considerations

1. **PatientIdentifierEnricher tests:** Use mocked `DiscoveredConfig` with known type names/UUIDs
2. **ConceptResolver tests:** WireMock stub for `/concept?source=LOINC&code=12345`
3. **FhirReferenceResolver tests:** WireMock stubs for `/Patient?identifier=...` and `/encountertype`
4. **FhirToRestTransformer tests:** Pure unit tests — no external dependencies
5. **ResourceRouter tests:** Mock both `OpenMrsFhirClient` and `OpenMrsRestClient`, verify ordering
6. **BundleSplitter tests:** Pure JSON parsing — test Bundle and standalone payloads
7. **VisitManager tests:** Mock `RestClient` for GET/POST visit calls, test skip conditions, caching, error handling
8. **SecurityConfigTest:** `@SpringBootTest` + `@AutoConfigureMockMvc` with two nested test classes:
   - `SecurityDisabledTest` — `cce.security.enabled=false` (default), verifies all endpoints open
   - `SecurityEnabledTest` — `@TestPropertySource(properties = "cce.security.enabled=true")`, verifies 401 without auth, 200 with valid Basic Auth, 401 with wrong credentials

---

## 20. Deployment

### Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Environment Variables

Override any `application.yml` property via Spring Boot conventions:

```bash
# Inbound API Security
CCE_SECURITY_ENABLED=true                    # Enable auth on adaptor endpoints
CCE_AUTH_USERNAME=cce-client                 # Basic Auth user for inbound API
CCE_AUTH_PASSWORD=strong-secret              # Basic Auth password for inbound API
CCE_OAUTH2_ISSUER_URI=http://keycloak:8180/realms/openmrs  # Enable inbound JWT validation

# Outbound OpenMRS connection
OPENMRS_FHIR_BASE_URL=http://openmrs:8080/openmrs/ws/fhir2/R4
OPENMRS_REST_BASE_URL=http://openmrs:8080/openmrs/ws/rest/v1
OPENMRS_AUTH_TYPE=basic                      # "basic" or "oauth2"
OPENMRS_USERNAME=admin                       # Used when AUTH_TYPE=basic
OPENMRS_PASSWORD=Admin123                    # Used when AUTH_TYPE=basic
OPENMRS_OAUTH2_TOKEN_URL=http://keycloak:8180/realms/openmrs/protocol/openid-connect/token  # Used when AUTH_TYPE=oauth2
OPENMRS_OAUTH2_CLIENT_ID=cce-adaptor         # Used when AUTH_TYPE=oauth2
OPENMRS_OAUTH2_CLIENT_SECRET=secret          # Used when AUTH_TYPE=oauth2
OPENMRS_OAUTH2_SCOPE=openid                  # Used when AUTH_TYPE=oauth2
SERVER_PORT=8083
```

### Health Checks

- Liveness: `GET /actuator/health/liveness`
- Readiness: `GET /actuator/health/readiness`
- Full: `GET /actuator/health`

---

## 21. Building From Scratch Checklist

When rebuilding this service in a new repo, implement in this order:

1. **Scaffold:** Spring Boot 3.4.x + Java 21 + Gradle + HAPI FHIR + Spring Security + OAuth2 Resource Server
2. **Config layer:** `OpenMrsProperties`, `RestClientConfig` (two RestClient beans with dual auth), `FhirConfig`, `SecurityConfig`, `OAuth2TokenProvider`
3. **Models:** `ResourceEntry`, `RoutingResult`, `ProcessingResponse` (all records)
4. **Exceptions:** `FhirParsingException`, `ResourceTransformException`, `OpenMrsClientException`, `GlobalExceptionHandler`
5. **FhirResourceParser:** HAPI FHIR parse/encode wrapper
6. **CapabilityStatementCache:** Refresh from `/metadata`, expose `canFhir()`
7. **BundleSplitter:** Bundle → `List<ResourceEntry>` with UUID detection
8. **ConceptResolver:** System-to-source mapping table + REST API lookup
9. **FhirReferenceResolver:** Reference resolution + concept code resolution + encounter type resolution
10. **RequiredFieldEnricher:** Default values for all 13 resource types
11. **FhirElementIdEnricher:** UUID injection for 18 array field types
12. **PatientIdentifierEnricher:** LuhnMod30 generation + source identifier promotion + on-demand type creation
13. **DiscoveredConfig + OpenMrsConfigDiscovery:** Auto-discovery at startup
14. **OpenMrsFhirClient:** FHIR client with enrichment pipeline + retry
15. **FhirToRestTransformer:** ServiceRequest→TestOrder, MedicationRequest→DrugOrder
16. **OpenMrsRestClient:** REST fallback with transformer + retry
17. **SourceIdMappingStore:** In-memory source-ID mapping (or remove if source owns mapping)
18. **ResourceRouter:** Dependency ordering + routing decision + source-ID mapping + cross-references
19. **InboundProcessingService:** Orchestrator: split → route → aggregate
20. **InboundResourceController:** POST /api/v1/fhir endpoint
21. **DiagnosticsController:** Capabilities + routing table endpoints
22. **Tests:** 143+ unit tests across all components (including security tests)
23. **Observability:** Micrometer counters/timers + MDC structured logging + Prometheus endpoint
