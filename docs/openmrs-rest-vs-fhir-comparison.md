# OpenMRS REST API vs FHIR API — Comprehensive Comparison

> **Context:** This document compares the two API layers exposed by OpenMRS — the REST API (`webservices.rest` module) and the FHIR R4 API (`fhir2` module) — covering architecture, resource coverage, data handling, polling capabilities, authentication, and recommendations for the CCE integration.

---

## 1. Architectural Overview

### REST API (`webservices.rest`)

| Aspect | Details |
|---|---|
| **Module** | `openmrs-module-webservices.rest` |
| **Base URL** | `/openmrs/ws/rest/v1` |
| **Introduced** | OpenMRS 1.9 (2012) |
| **Design** | 1:1 mirror of Java domain classes |
| **Response Format** | Custom OpenMRS JSON (non-standard) |
| **Status** | Maintenance mode — bug fixes and security patches only, no new features |

### FHIR API (`fhir2`)

| Aspect | Details |
|---|---|
| **Module** | `openmrs-module-fhir2` |
| **Base URL** | `/openmrs/ws/fhir2/R4` |
| **Introduced** | OpenMRS 2.4 (2019) — replaced older DSTU2 `fhir` module |
| **Design** | Synthetic FHIR R4 mappings — assembles standard resources from domain tables |
| **Response Format** | Standard FHIR R4 JSON (HL7-compliant) |
| **Status** | Active development — new resources, search params, and GSoC projects ongoing |

### How They Relate to the Database

```
┌─────────────────────────────────────────────────┐
│              OpenMRS Database                   │
│  ┌─────────┐ ┌───────────┐ ┌─────┐ ┌───────┐  │
│  │ patient │ │ encounter │ │ obs │ │ order │  │
│  └────┬────┘ └─────┬─────┘ └──┬──┘ └───┬───┘  │
│       │             │          │         │      │
├───────┼─────────────┼──────────┼─────────┼──────┤
│       │    OpenMRS Core (Java Domain Classes)   │
│  Patient.java  Encounter.java  Obs.java  Order  │
├─────────────────────┬───────────────────────────┤
│                     │                           │
│  ┌──────────────────┼────────────────────────┐  │
│  │   REST Module    │    FHIR2 Module        │  │
│  │   (1:1 mapping)  │    (synthetic mapping) │  │
│  │                  │                        │  │
│  │  /patient        │    /Patient            │  │
│  │  /encounter      │    /Encounter          │  │
│  │  /obs            │    /Observation         │  │
│  │  /order          │    ✗ (no Order)        │  │
│  │  ✗ (no Immun.)  │    /Immunization        │  │
│  └──────────────────┼────────────────────────┘  │
│                     │                           │
│            HTTP Layer (Tomcat)                   │
└─────────────────────────────────────────────────┘
```

---

## 2. Resource Coverage Comparison

### Resources Available via FHIR Only (Synthetic Mappings)

These resources have **no dedicated database table** — the `fhir2` module constructs them from existing tables (primarily `obs`).

| FHIR Resource | Mapped From | Why No REST Endpoint |
|---|---|---|
| `Immunization` | `obs` rows with vaccine concepts (CIEL:984, CIEL:1410) | No `Immunization.java` domain class |
| `DiagnosticReport` | `encounter` + grouped `obs` rows | No `DiagnosticReport.java` domain class |
| `Procedure` | `obs` rows with procedure concepts | No `Procedure.java` domain class |
| `MedicationAdministration` | `obs` rows with medication concepts | No `MedicationAdministration.java` domain class |
| `MedicationDispense` | `obs` or dedicated dispensing module | Added in fhir2 2.x; no REST equivalent |
| `Task` | Dedicated `fhir_task` table (added by fhir2 module) | Table created by fhir2 itself — REST module doesn't know about it |

### Resources Available via REST Only (No FHIR Mapping)

| REST Endpoint | Why No FHIR Endpoint |
|---|---|
| `/order` (TestOrder, DrugOrder) | Order model is too complex — multiple types, care settings, order groups, discontinuation logic. FHIR's `ServiceRequest`/`MedicationRequest` mapping was never completed |
| `/programenrollment` | No standard FHIR resource for program enrollment (would need CarePlan or custom) |
| `/visit` | FHIR represents Visits as special `Encounter` resources without `type`, making it ambiguous |
| `/form` | No FHIR equivalent for form definitions |
| `/personattribute` | Partially in FHIR `Patient.extension`, but not round-trippable |
| `/cohort` | No FHIR equivalent (would be `Group`) |
| `/reportingrest/*` | Reporting module — no FHIR equivalent |
| `/idgen/*` | ID generation — no FHIR equivalent |
| `/patientidentifiertype` | Admin/config resource — no FHIR equivalent |
| `/encountertype` | Admin/config resource — no FHIR equivalent |
| `/visittype` | Admin/config resource — no FHIR equivalent |
| `/systemsetting` | Admin/config resource — no FHIR equivalent |

### Resources Available via Both APIs

| Resource | REST Endpoint | FHIR Endpoint | Notes |
|---|---|---|---|
| Patient | `/patient` | `/Patient` | FHIR adds synthetic Immunization history |
| Encounter | `/encounter` | `/Encounter` | FHIR merges Visit + Encounter into one type |
| Observation | `/obs` | `/Observation` | Same underlying data |
| Condition | `/condition` (2.5+) | `/Condition` | Both map to `condition` table |
| Location | `/location` | `/Location` | Same data |
| Practitioner | `/provider` | `/Practitioner` | Different naming |
| Medication | `/drug` | `/Medication` | Different naming |
| AllergyIntolerance | `/allergy` | `/AllergyIntolerance` | Different naming |
| Person | `/person` | N/A (embedded in Patient) | REST exposes separately |

---

## 3. Data Format Comparison

### Same Patient — Two Different Representations

**REST API** (`GET /ws/rest/v1/patient/{uuid}?v=full`):
```json
{
  "uuid": "abc-123",
  "display": "10001X - John Doe",
  "person": {
    "uuid": "abc-123",
    "gender": "M",
    "age": 35,
    "birthdate": "1991-03-15T00:00:00.000+0000",
    "preferredName": {
      "givenName": "John",
      "familyName": "Doe"
    },
    "preferredAddress": {
      "cityVillage": "Kigali",
      "country": "Rwanda"
    }
  },
  "identifiers": [
    {
      "uuid": "id-uuid-1",
      "identifier": "10001X",
      "identifierType": {
        "uuid": "type-uuid",
        "display": "OpenMRS ID"
      },
      "preferred": true
    }
  ],
  "auditInfo": {
    "creator": {"uuid": "...", "display": "admin"},
    "dateCreated": "2025-01-15T10:30:00.000+0000",
    "changedBy": {"uuid": "...", "display": "admin"},
    "dateChanged": "2025-06-20T14:00:00.000+0000"
  }
}
```

**FHIR API** (`GET /ws/fhir2/R4/Patient/{uuid}`):
```json
{
  "resourceType": "Patient",
  "id": "abc-123",
  "meta": {
    "lastUpdated": "2025-06-20T14:00:00.000+00:00"
  },
  "identifier": [
    {
      "id": "id-uuid-1",
      "use": "official",
      "type": {
        "coding": [{"code": "type-uuid"}],
        "text": "OpenMRS ID"
      },
      "value": "10001X"
    }
  ],
  "name": [
    {
      "family": "Doe",
      "given": ["John"]
    }
  ],
  "gender": "male",
  "birthDate": "1991-03-15",
  "address": [
    {
      "city": "Kigali",
      "country": "Rwanda"
    }
  ]
}
```

### Key Format Differences

| Aspect | REST | FHIR |
|---|---|---|
| ID field | `uuid` | `id` |
| Naming | `person.preferredName.givenName` | `name[0].given[0]` |
| Gender | `"M"` / `"F"` | `"male"` / `"female"` |
| Date format | `"2025-01-15T10:30:00.000+0000"` | `"2025-01-15T10:30:00.000+00:00"` |
| Audit info | `auditInfo.dateCreated` / `auditInfo.dateChanged` | `meta.lastUpdated` |
| Identifiers | `identifiers[].identifierType.display` | `identifier[].type.text` |
| Codes/Concepts | `concept.uuid` | `code.coding[].code` with `system` |
| References | `{"uuid": "..."} ` or inline object | `{"reference": "Patient/uuid"}` |
| Collections | `{"results": [...]}` | FHIR Bundle with `entry[]` |
| Pagination | `startIndex`, `limit`, `totalCount` | `_count`, `Link` headers, Bundle pages |

---

## 4. Search & Query Capabilities

### REST API Search Parameters

| Endpoint | Available Params | Example |
|---|---|---|
| `/patient` | `q` (name search), `identifier`, `v` | `?q=John&v=full` |
| `/encounter` | `patient`, `fromdate`, `todate`, `encounterType`, `v` | `?patient={uuid}&fromdate=2025-01-01` |
| `/obs` | `patient`, `concept`, `v` | `?patient={uuid}&concept={conceptUuid}` |
| `/order` | `patient`, `type`, `careSetting`, `v` | `?patient={uuid}&type=testorder` |
| `/visit` | `patient`, `includeInactive` | `?patient={uuid}&includeInactive=false` |
| `/location` | `tag`, `q`, `v` | `?tag=Login+Location` |
| `/concept` | `source`, `code`, `q`, `v` | `?source=LOINC&code=8480-6` |

**REST Representations:**
- `v=default` — basic fields
- `v=full` — all fields including audit info
- `v=custom:(field1,field2)` — selective fields (reduces payload)

### FHIR API Search Parameters

| Endpoint | Available Params | Example |
|---|---|---|
| `/Patient` | `identifier`, `name`, `given`, `family`, `gender`, `birthdate`, `_lastUpdated` | `?identifier=10001X` |
| `/Encounter` | `patient`, `date`, `type`, `_lastUpdated`, `_include` | `?patient={uuid}&date=gt2025-01-01` |
| `/Observation` | `patient`, `code`, `date`, `_lastUpdated`, `category` | `?patient={uuid}&code=http://loinc.org\|8480-6` |
| `/Condition` | `patient`, `code`, `_lastUpdated` | `?patient={uuid}` |
| `/Immunization` | `patient`, `_lastUpdated` | `?patient={uuid}` |
| `/DiagnosticReport` | `patient`, `code`, `_lastUpdated` | `?patient={uuid}` |

**FHIR Modifiers:**
- `_count=50` — page size
- `_sort=-_lastUpdated` — sort descending
- `_include=Observation:patient` — include referenced resources
- `_lastUpdated=gt2025-01-01` — delta filtering
- `_summary=count` — return count only

### Comparison

| Capability | REST | FHIR |
|---|---|---|
| Text search | `q=` (full-text) | `name=` (structured) |
| Date range queries | `fromdate`/`todate` (some endpoints) | `date=gt{}&date=lt{}` (standard prefix) |
| Delta queries (changes since) | No native support | `_lastUpdated=gt{timestamp}` |
| Include related resources | Must make separate calls | `_include` / `_revinclude` |
| Custom field selection | `v=custom:(field1,field2)` | `_elements=field1,field2` (limited) |
| Sorting | Limited / none on most endpoints | `_sort` on supported params |
| Result count | `totalCount` in response | `Bundle.total` |
| Chained search | Not supported | `patient.identifier=NID\|12345` |

---

## 5. Polling & Change Detection

### For Emitter Adaptor Use Case

| Capability | REST API | FHIR API |
|---|---|---|
| **Delta detection** | No native `changedAfter` param — must fetch all + filter by `auditInfo.dateChanged` | `_lastUpdated=gt{timestamp}` — server-side filtering |
| **Deleted/voided records** | `includeAll=true` returns voided records with `voided: true` flag | Invisible — deleted resources simply vanish from results |
| **Pagination model** | Offset-based: `startIndex=0&limit=50` | Cursor/link-based: Bundle `next` page links |
| **Pagination reliability** | Unstable — inserts between pages cause missed/duplicate records | More reliable with `next` links, but still has edge cases |
| **Sort by modification** | Not supported on most endpoints | `_sort=-_lastUpdated` |
| **Change feed** | No equivalent | No native support (OpenMRS lacks FHIR Subscription) |
| **Atom Feed (external)** | Available via `atomfeed` module — sequential, reliable | Not available via FHIR module |

### REST Polling Strategy (if needed)

```
# No efficient delta polling — must fetch all and filter client-side
GET /ws/rest/v1/obs?patient={uuid}&v=full

# Then filter in code:
for obs in results:
    if obs.auditInfo.dateChanged > lastPollTime:
        process(obs)
```

**Problems:**
- Fetches entire dataset every poll cycle
- No server-side date filtering on most endpoints
- High bandwidth and latency
- Must paginate through all records

### FHIR Polling Strategy (recommended)

```
# Efficient server-side delta query
GET /ws/fhir2/R4/Observation?_lastUpdated=gt2026-04-05T00:00:00&_count=50&_sort=-_lastUpdated

# Page through results via Bundle.link[rel="next"]
# Deduplicate by resource ID
# Store max(_lastUpdated) as checkpoint
```

**Advantages:**
- Server filters — only changed records returned
- Standard pagination via Bundle links
- Sortable by modification time
- Deduplication by resource `id`

### FHIR Polling Limitations

| Limitation | Impact | Mitigation |
|---|---|---|
| `_lastUpdated` inconsistency | Some resources may not update `meta.lastUpdated` on all field changes | Add 5-second overlap window to polling |
| Deleted resources invisible | Cannot detect deletions via FHIR polling | Use REST `includeAll=true` for periodic delete reconciliation |
| No `_history` support | OpenMRS `fhir2` module doesn't implement version history | Track changes externally |
| Pagination for large datasets | Performance degrades beyond ~500 records per page | Use `_count=50` and page through |
| `_sort` limited | Only `_lastUpdated` and a few params supported | Structure queries to minimize sort needs |
| Timing gaps | Records created between poll cycles may be missed if using exact timestamps | Overlap window + deduplication |
| Bundle size | Large bundles (>100 entries) can timeout | Use smaller `_count` values |
| Encounter/Visit confusion | Visits and clinical encounters both appear as `Encounter` resources | Filter by `type` or `meta.tag` |

---

## 6. Write Operations (Receiver Adaptor Context)

### Creating Resources

| Operation | REST | FHIR |
|---|---|---|
| Create Patient | `POST /patient` with OpenMRS JSON | `POST /Patient` with FHIR JSON |
| Create Encounter | `POST /encounter` | `POST /Encounter` |
| Create Observation | `POST /obs` | `POST /Observation` |
| Create Immunization | Must POST as `obs` with correct concept codes | `POST /Immunization` — module handles mapping |
| Create Order | `POST /order` with `type: "testorder"` or `"drugorder"` | Not supported |
| Create Visit | `POST /visit` | Possible but confusing (Visit = Encounter without type) |
| Create Condition | `POST /condition` (2.5+) | `POST /Condition` |

### Update Resources

| Operation | REST | FHIR |
|---|---|---|
| Update | `POST /patient/{uuid}` (yes, POST for updates) | `PUT /Patient/{uuid}` |
| Partial update | Supported — send only fields to change | Not supported — must send full resource |
| Delete | `DELETE /patient/{uuid}` (voids) | `DELETE /Patient/{uuid}` (voids) |
| Purge | `DELETE /patient/{uuid}?purge=true` (permanent) | Not supported |

### What Our Receiver Adaptor Does

| Step | API Used | Why |
|---|---|---|
| Patient create/update | FHIR | Standard format, synthetic identifier handling |
| Encounter create | FHIR | Includes Visit linkage via `partOf` |
| Observation create | FHIR | Direct mapping to `obs` table |
| Immunization create | FHIR | Synthetic mapping — no REST equivalent |
| DiagnosticReport create | FHIR | Synthetic mapping — no REST equivalent |
| Condition create | FHIR | Cleaner API |
| AllergyIntolerance create | FHIR | Cleaner API |
| ServiceRequest (TestOrder) | REST | FHIR doesn't support Orders |
| MedicationRequest (DrugOrder) | REST | FHIR doesn't support Orders |
| Patient identifier type creation | REST | Admin config — no FHIR equivalent |
| Visit creation | REST | More reliable than FHIR Visit model |
| Encounter type lookup | REST | No FHIR search for encounter types |
| Concept lookup | REST | `source` + `code` search not in FHIR |
| Location discovery | REST | Tag-based search |

---

## 7. Authentication & Security

| Aspect | REST | FHIR |
|---|---|---|
| Basic Auth | Supported (username/password) | Supported (same credentials) |
| Session Auth | `JSESSIONID` cookie | `JSESSIONID` cookie |
| OAuth2 (Keycloak) | Supported via `oauth2login` module | Supported via same module |
| Per-endpoint permissions | Based on OpenMRS Privileges (granular) | Based on OpenMRS Privileges (same) |
| CORS | Configured in `web.xml` / filter | Same configuration |
| Rate limiting | None built-in | None built-in |

Both APIs use the **same authentication layer** — the OpenMRS session/privilege system. There is no difference in auth handling between REST and FHIR.

---

## 8. Performance Characteristics

| Metric | REST | FHIR |
|---|---|---|
| Response time (simple read) | ~50-150ms | ~100-300ms |
| Response time (search) | ~100-500ms | ~200-800ms |
| Payload size (Patient) | ~2KB (default), ~5KB (full) | ~3KB (standard) |
| Payload size (custom) | `v=custom:()` reduces significantly | `_elements` limited reduction |
| Batch operations | Not supported | Bundle transactions (limited in OpenMRS) |
| Connection overhead | Standard HTTP | Standard HTTP (same) |

**Why FHIR is slower:**
- Additional mapping layer (domain → FHIR conversion)
- UUID lookups for concept codes → FHIR coding
- Reference resolution (internal UUID → `ResourceType/UUID` format)
- The `fhir2` module does extra work that REST doesn't need

**When REST is measurably faster:**
- High-volume observation reads (REST `/obs` skips FHIR concept mapping)
- Patient searches with `v=custom:()` (minimal payload)
- Admin/config operations (direct domain mapping)

---

## 9. Error Handling

### REST Error Responses

```json
{
  "error": {
    "message": "Patient#null failed to validate with reason: ...",
    "code": "org.openmrs.api.ValidationException",
    "detail": "..."
  }
}
```

### FHIR Error Responses (OperationOutcome)

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "error",
      "code": "processing",
      "diagnostics": "Patient#null failed to validate with reason: ..."
    }
  ]
}
```

| Aspect | REST | FHIR |
|---|---|---|
| Error format | Custom JSON | Standard FHIR OperationOutcome |
| HTTP status codes | 400, 404, 500 (sometimes wrong) | 400, 404, 422, 500 (more accurate) |
| Validation messages | Variable quality | Structured `issue[]` array |
| Stack traces | Sometimes leaked in `detail` | Typically hidden |

---

## 10. OpenMRS Version History

### REST API Timeline

| OpenMRS Version | REST Module Version | Notable Changes |
|---|---|---|
| 1.9 (2012) | 2.0 | Initial release — Patient, Encounter, Obs, Order |
| 1.11 (2014) | 2.12 | Added more resources (Allergy, Drug, etc.) |
| 2.0 (2017) | 2.20 | Stable API — minor additions |
| 2.3 (2019) | 2.28 | Added Condition resource |
| 2.5-2.7 (2021-2024) | 2.36+ | Maintenance fixes only |
| 2.8 (2025) | 2.44+ | ProviderRole endpoint added (mirrors new core class) |

### FHIR API Timeline

| OpenMRS Version | FHIR Module | Notable Changes |
|---|---|---|
| 1.11-2.3 (2014-2019) | `fhir` 1.x (DSTU2) | Old module — limited, DSTU2 only |
| 2.4 (2019) | `fhir2` 1.0 | New module — FHIR R4, Patient, Encounter, Observation |
| 2.5 (2021) | `fhir2` 1.4 | Added Immunization, DiagnosticReport, Procedure |
| 2.6 (2022) | `fhir2` 1.6 | Added AllergyIntolerance, MedicationDispense |
| 2.7 (2023-2024) | `fhir2` 1.8+ | Added Task, improved search params, `_lastUpdated` |
| 2.8 (2025) | `fhir2` 2.0+ | Matured — more synthetic mappings, better `_include` |

### Key Insight

The REST module has been essentially **the same since 2017** (OpenMRS 2.0). The FHIR module has been **rapidly evolving since 2019** and now covers more clinical resource types than REST, even though REST covers more administrative/config resources.

---

## 11. Interoperability Comparison

| Scenario | REST | FHIR |
|---|---|---|
| Exchange data with national HIE | Requires custom transformation | Native — FHIR is the standard |
| Send data to DHIS2 | Custom integration | FHIR-to-DHIS2 adapters exist |
| Integrate with HAPI FHIR Server | Must transform REST → FHIR | Direct compatibility |
| Connect to SHR (Shared Health Record) | Custom transformation | Native FHIR exchange |
| Connect to SPICE/eBuzima | Custom mapping needed | FHIR R4 is the agreed format |
| Use with OpenHIM mediator | Possible but custom | FHIR channels built-in |
| Bulk data export | Not supported | FHIR Bulk Data (not in OpenMRS yet) |

---

## 12. Summary: When to Use Which API

### Use FHIR When:

- Creating/reading standard clinical resources (Patient, Observation, Encounter, Condition, Immunization, etc.)
- Polling for changes (`_lastUpdated` filtering)
- Exchanging data with external systems (HIEs, SHRs, SPICE)
- Building the Emitter Adaptor (delta polling)
- Interoperability is a priority
- You want standard FHIR R4 output without transformation

### Use REST When:

- Managing Orders (TestOrder, DrugOrder) — FHIR doesn't support them
- Administrative operations (creating identifier types, encounter types, visit types)
- Concept lookups by source code (`GET /concept?source=LOINC&code=8480-6`)
- Visit management (clearer model than FHIR)
- Detecting deleted/voided records (`includeAll=true`)
- Program enrollment management
- Reporting (`reportingrest` module)
- ID generation (`idgen` module)
- Custom field selection (`v=custom:()`)
- Maximum performance for high-volume reads

### Our Adaptor's Strategy (Recommended)

```
FHIR (primary)        ──→  ~80% of operations
  ├── Patient CRUD
  ├── Encounter CRUD
  ├── Observation CRUD
  ├── Condition CRUD
  ├── Immunization CRUD
  ├── DiagnosticReport CRUD
  ├── AllergyIntolerance CRUD
  ├── Procedure CRUD
  ├── MedicationDispense CRUD
  ├── Task CRUD
  └── Delta polling (_lastUpdated)

REST (fallback)       ──→  ~20% of operations
  ├── ServiceRequest → TestOrder
  ├── MedicationRequest → DrugOrder
  ├── Concept resolution
  ├── Encounter type lookup
  ├── Identifier type management
  ├── Visit creation
  ├── Location discovery
  └── Delete reconciliation
```

---

## 13. Decision Matrix for Emitter Adaptor

| Resource to Poll | Recommended API | Reason |
|---|---|---|
| Patient | FHIR | `_lastUpdated` works, standard format |
| Encounter | FHIR | `_lastUpdated` works, includes Visit linkage |
| Observation | FHIR | `_lastUpdated` works, concept codes in standard format |
| Condition | FHIR | `_lastUpdated` works |
| Immunization | FHIR | Only available via FHIR |
| DiagnosticReport | FHIR | Only available via FHIR |
| AllergyIntolerance | FHIR | `_lastUpdated` works |
| Procedure | FHIR | Only available via FHIR |
| MedicationDispense | FHIR | Only available via FHIR |
| Orders (TestOrder/DrugOrder) | REST | Not available via FHIR |
| Program Enrollments | REST | Not available via FHIR |
| Deleted/Voided Records | REST | FHIR hides deleted resources |

### Recommended Emitter Polling Architecture

```
┌─────────────────────────────────────────────┐
│            Emitter Adaptor                  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │  FHIR Poller (primary)               │  │
│  │  - Patient, Encounter, Observation,  │  │
│  │    Condition, Immunization, etc.      │  │
│  │  - Uses _lastUpdated=gt{checkpoint}  │  │
│  │  - Runs every 30-60 seconds          │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │  REST Poller (supplementary)         │  │
│  │  - Orders (testorder/drugorder)      │  │
│  │  - Program enrollments              │  │
│  │  - Runs every 60-120 seconds        │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │  Delete Reconciler (periodic)        │  │
│  │  - REST with includeAll=true         │  │
│  │  - Detects voided records            │  │
│  │  - Runs every 5-15 minutes           │  │
│  └───────────────────────────────────────┘  │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │  Checkpoint Store                    │  │
│  │  - Persists lastPollTime per type    │  │
│  │  - Survives restarts                 │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

---

*Document generated: April 2026*
*Applicable to: OpenMRS Platform 2.7.x / 2.8.x with fhir2 module*
