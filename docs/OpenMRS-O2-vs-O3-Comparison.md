# OpenMRS 2.x vs O3 — FHIR Capability Comparison

> **Date:** 3 April 2026  
> **Source:** Live data from running instances (2.x RefApp on port 9095, O3 RefApp on port 9096)

---

## 1. Platform Overview

| | **OpenMRS 2.x (RefApp)** | **OpenMRS 3 (O3 RefApp)** |
|---|---|---|
| **HAPI FHIR Server** | v5.4.0 | v5.7.9 |
| **FHIR Version** | R4 (4.0.1) | R4 (4.0.1) |
| **Total FHIR Resources** | **17** | **22** (+5 new) |
| **Database** | MySQL 5.7 | MariaDB 10.11 |
| **Docker Port (our setup)** | 9095 | 9096 |
| **Auth** | admin / Admin123 | admin / Admin123 |
| **FHIR Endpoint** | `/openmrs/ws/fhir2/R4` | `/openmrs/ws/fhir2/R4` |
| **REST Endpoint** | `/openmrs/ws/rest/v1` | `/openmrs/ws/rest/v1` |
| **UI** | Legacy JSP Admin UI | Modern SPA (React, Carbon Design) |

---

## 2. FHIR Resource Comparison

**Legend:** C = Create, R = Read, U = Update, D = Delete, P = Patch, S = Search

| Resource | 2.x (CRUDPS) | O3 (CRUDPS) | Delta |
|---|---|---|---|
| **AllergyIntolerance** | CRUD-S | CRUDPS | +patch |
| **Condition** | CRUD-S | CRUDPS | +patch |
| **DiagnosticReport** | CRUD-S | CRUDPS | +patch |
| **Encounter** | CRUD-S | CRUDPS | +patch |
| **EpisodeOfCare** | — | -R---- | **NEW** (read-only) |
| **Flag** | — | -R---S | **NEW** (read-only) |
| **Group** | CRUD-S | CRUD-S | _(same)_ |
| **Immunization** | CRUD-S | CRUDPS | +patch |
| **Invoice** | — | -R---- | **NEW** (read-only) |
| **Location** | CRUD-S | CRUDPS | +patch |
| **Medication** | CRUD-S | CRUDPS | +patch |
| **MedicationDispense** | — | CRUDPS | **NEW** (full CRUD) |
| **MedicationRequest** | -R---S | -R--PS | +patch |
| **Observation** | CR-D-S | CRUDPS | **+update**, +patch |
| **OperationDefinition** | -R---- | -R---- | _(same)_ |
| **Patient** | CRUD-S | CRUDPS | +patch |
| **Person** | CRUD-S | CRUDPS | +patch |
| **Practitioner** | CRUD-S | CRUDPS | +patch |
| **RelatedPerson** | -R---S | -R---S | _(same)_ |
| **ServiceRequest** | -R---S | -R---S | _(same)_ |
| **Task** | CRUD-S | CRUDPS | +patch |
| **ValueSet** | — | -R---S | **NEW** (read-only) |

### Summary of Changes

- **5 new resources in O3:** EpisodeOfCare, Flag, Invoice, MedicationDispense, ValueSet
- **Patch support** added to all writable resources in O3 (14 resources gained `patch`)
- **Observation update** now supported in O3 (was create/delete only in 2.x)
- **MedicationDispense** is the only new resource with full CRUD support
- **No resources removed** — O3 is a strict superset of 2.x

---

## 3. Patient Identifier Types

### 2.x (6 types)

| Name | Notes |
|---|---|
| OpenMRS ID | Primary, Luhn Mod 30 validated |
| NID | Added by adaptor for RHIE |
| Old Identification Number | Legacy |
| OpenEMPI ID | Enterprise MPI integration |
| OpenMRS Identification Number | Internal |
| UPI | Added by adaptor for RHIE |

### O3 (8 types)

| Name | Notes |
|---|---|
| OpenMRS ID | Primary, Luhn Mod 30 validated |
| ID Card | Pre-installed |
| Legacy ID | Pre-installed |
| NID | Added by adaptor for RHIE |
| Old Identification Number | Legacy |
| OpenMRS Identification Number | Internal |
| SSN | Pre-installed |
| UPI | Added by adaptor for RHIE |

---

## 4. Key Behavioral Differences

### 4.1 Patient Identifier Mapping

This is the **most critical difference** for integrations.

| Aspect | 2.x Behavior | O3 Behavior |
|---|---|---|
| **How FHIR identifiers map to DB** | `identifier.type.text` → `patient_identifier_type.name` (string match) | `identifier.type.coding[0].code` → `patient_identifier_type.uuid` (UUID match) |
| **Non-URI `system` values** (e.g., `"NID"`, `"UPI"`) | Ignored — identifiers persist fine | **Silently drops** the entire identifier |
| **Missing `type.coding`** | Works — only `type.text` needed | Identifier **not persisted** |
| **Missing `type.text`** | Identifier **not persisted** | Works if `type.coding` is present |

**Impact:** A payload that works on 2.x will silently lose identifiers on O3 if it only has `type.text` without `type.coding`, or if it has non-URI `system` values like `"system": "NID"`.

### 4.2 Adaptor Compatibility Solution

The CCE Receiver Adaptor handles both versions with a single code path:

```
Incoming RHIE identifier:
  { "system": "NID", "value": "1192880005226001" }

Enriched output (works on both 2.x AND O3):
  {
    "value": "1192880005226001",          ← value preserved
    "type": {
      "text": "NID",                       ← for 2.x (name match)
      "coding": [{
        "code": "8d0e99db-9179-..."        ← for O3 (UUID match)
      }]
    },
    "use": "secondary",                   ← non-preferred
    "extension": [{                       ← required location
      "url": "http://fhir.openmrs.org/ext/patient/identifier#location",
      "valueReference": { "reference": "Location/<uuid>" }
    }]
  }
  // NOTE: "system" field is REMOVED after enrichment
  //   - 2.x never used it for mapping
  //   - O3 rejects non-URI values and drops the identifier
```

### 4.3 Visit Handling

| Aspect | 2.x | O3 |
|---|---|---|
| **Visit resource** | Separate REST API (`/ws/rest/v1/visit`) | Auto-created from Encounter |
| **Visit window** | Must be created explicitly before Encounter | Encounter `period.start` triggers auto-visit |
| **Visit types** | Must be pre-configured | Default visit type used |

### 4.4 Concept / Code Resolution

| Aspect | 2.x | O3 |
|---|---|---|
| **LOINC mappings** | Pre-installed (Reference Application module) | Pre-installed (same CIEL dictionary) |
| **SNOMED CT mappings** | Limited | Same CIEL dictionary |
| **Concept search API** | `GET /concept?source=LOINC&code=8480-6` | Same endpoint, same behavior |
| **FHIR code format** | `coding: [{"code": "<uuid>"}]` (no system) | Same format |

### 4.5 Encounter Types

| Aspect | 2.x | O3 |
|---|---|---|
| **Default types** | Fewer built-in types | More built-in types (Visit Note, Vitals, etc.) |
| **Visit Note UUID** | `d7151f82-c1f3-4152-a605-2f9ea7414a79` | Same UUID |
| **Auto-creation** | Encounter type must exist | Same |

---

## 5. Resources That Require REST Fallback

Both versions have read-only FHIR resources that require REST API for create/update:

| Resource | FHIR Status (both versions) | REST Fallback |
|---|---|---|
| **ServiceRequest** | Read + Search only | `POST /ws/rest/v1/order` (TestOrder) |
| **MedicationRequest** | Read + Search only | `POST /ws/rest/v1/order` (DrugOrder) |

The adaptor's `FhirToRestTransformer` handles this transparently via `ResourceRouter`.

---

## 6. Adaptor Architecture — Version-Agnostic Design

The CCE Receiver Adaptor is designed to work with **any OpenMRS FHIR-enabled instance** without hardcoded values:

### Auto-Discovery at Startup (`OpenMrsConfigDiscovery`)

| What | How | Used By |
|---|---|---|
| Location UUID | `GET /location?tag=Login+Location` | `PatientIdentifierEnricher` |
| Identifier Type Name + UUID | `GET /patientidentifiertype` | `PatientIdentifierEnricher` |
| Idgen Source UUID | `GET /idgen/identifiersource` | `PatientIdentifierEnricher` |
| Source Identifier Types + UUIDs | `GET /patientidentifiertype` (all non-primary) | `PatientIdentifierEnricher` |
| FHIR Capabilities | `GET /metadata` (CapabilityStatement) | `ResourceRouter` |

### Dynamic Routing (`ResourceRouter` + `CapabilityStatementCache`)

```
Incoming FHIR resource
  → CapabilityStatementCache: Does this resource support "create"?
    → YES: Route via FHIR endpoint (OpenMrsFhirClient)
    → NO:  Route via REST endpoint (OpenMrsRestClient + FhirToRestTransformer)
```

This means if O3 adds `create` support for `ServiceRequest` in a future version, the adaptor will automatically route it via FHIR without code changes.

---

## 7. Migration Considerations (2.x → O3)

If switching the adaptor from 2.x to O3:

| Step | Required? | Details |
|---|---|---|
| Change base URL | **Yes** | `application.yml`: update `openmrs.fhir.base-url` and `openmrs.rest.base-url` |
| Register NID/UPI types | **Auto** | Adaptor's `OpenMrsConfigDiscovery` auto-registers them if missing |
| Restart adaptor | **Yes** | To pick up new identifier type UUIDs |
| Code changes | **None** | Adaptor is version-agnostic |
| Data migration | **Separate** | Patient data does not transfer between instances |

---

## 8. Appendix: Identifier Enrichment Pipeline

```
Incoming RHIE Patient JSON
  │
  ├─ FhirResourceParser.parse()          Parse JSON → HAPI FHIR object
  ├─ BundleSplitter.split()              Extract individual resources
  │     └─ UUID check: non-UUID IDs → POST (not PUT)
  │
  ├─ FhirReferenceResolver.resolve()     Resolve external refs to OpenMRS UUIDs
  ├─ RequiredFieldEnricher.enrich()      Add missing required fields
  ├─ PatientIdentifierEnricher.enrich()  ← KEY STEP
  │     ├─ Generate OpenMRS ID (Luhn Mod 30)
  │     ├─ Set type.text (for 2.x)
  │     ├─ Set type.coding[0].code (for O3)
  │     ├─ Remove system field (O3 rejects non-URI)
  │     └─ Add location extension
  ├─ FhirElementIdEnricher.enrich()      Add UUID ids to sub-elements
  │
  └─ OpenMrsFhirClient.send()           POST/PUT to OpenMRS FHIR endpoint
```
