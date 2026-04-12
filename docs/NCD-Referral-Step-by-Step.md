# NCD Referral — Step-by-Step E2E Workflow

> **SPICE → CCE Adaptor → OpenMRS O3 → Lab → Results**

This document walks through the complete NCD (Non-Communicable Disease) screening-to-lab-results workflow. Each step includes the API call, expected response, and what happens behind the scenes.

---

## Prerequisites

| Component | URL | Status |
|---|---|---|
| CCE Receiver Adaptor | `http://localhost:8083` | Running |
| OpenMRS O3 | `http://localhost:9096` | Running |
| O3 FHIR API | `http://localhost:9096/openmrs/ws/fhir2/R4` | |
| O3 REST API | `http://localhost:9096/openmrs/ws/rest/v1` | |
| Auth | `admin` / `Admin123` | |

### Verified O3 Entities

| Entity | UUID |
|---|---|
| **Encounter Type: Visit Note** | `d7151f82-c1f3-4152-a605-2f9ea7414a79` |
| **Encounter Type: Lab Results** | `3596fafb-6f6f-4396-8c87-6e63a0f1bd71` |
| **Location: Outpatient Clinic** | `44c3efb0-2583-4c80-a79e-1f756a03c0a1` |
| **Location: Inpatient Ward** | `ba685651-ed3b-4e63-9b35-78893060758a` |
| **Practitioner: Jane Nurse** | `1fee2f21-82f3-4aab-8d87-f1cf19034649` |
| **Practitioner: June Technician** | `92416875-5fe2-4d3b-8531-e2c8c846a69d` |
| **Concept: Systolic BP (CIEL 5085)** | `5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| **Concept: Diastolic BP (CIEL 5086)** | `5086AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| **Concept: Fasting Glucose (CIEL 160912)** | `160912AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| **Concept: HbA1c (CIEL 159644)** | `159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| **Concept: T2DM (CIEL 142473)** | `142473AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| **Order Type: Test Order** | `52a447d3-a64a-11e3-9aeb-50e549534c5e` |

---

## Workflow Overview

```
┌────────────────────────────────────────────────────────────────┐
│                        SPICE (Source)                          │
│  Steps 1–5: Patient → Encounter → Vitals → Dx → Lab Referral │
│     All via CCE Adaptor (POST /api/v1/fhir)                   │
└──────────────────────────┬─────────────────────────────────────┘
                           │
                    ┌──────▼──────┐
                    │ CCE Adaptor │  Enriches, resolves, routes
                    │  :8083      │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  OpenMRS O3 │  Stores in FHIR / REST
                    │  :9096      │
                    └──────┬──────┘
                           │
┌──────────────────────────▼─────────────────────────────────────┐
│                    Lab System                                  │
│  Steps 6, 8: Accept/Complete Task → Direct O3 FHIR (PUT)      │
│  Steps 7a–c: Lab Results → via Adaptor (auto-links Visit)      │
└────────────────────────────────────────────────────────────────┘
                           │
                    ┌──────▼──────┐
                    │  Verify     │  Query O3 for full patient
                    │  Step 9     │  record and referral results
                    └─────────────┘
```

---

## Step 0 — Health Checks

### 0a. CCE Adaptor Health

```bash
curl -s http://localhost:8083/actuator/health | jq .
```

**Expected:**
```json
{"status": "UP"}
```

### 0b. OpenMRS O3 FHIR Endpoint

```bash
curl -s -u admin:Admin123 \
  http://localhost:9096/openmrs/ws/fhir2/R4/metadata \
  | jq '.software.name'
```

**Expected:** `"OpenMRS FHIR Server"` (or similar)

### 0c. Adaptor Routing Table

```bash
curl -s http://localhost:8083/api/v1/routing-table | jq .
```

Shows which resources route via FHIR vs REST. `ServiceRequest` and `MedicationRequest` are force-routed to REST.

---

## Step 1 — Register Patient (SPICE → Adaptor)

**Scenario:** SPICE registers a new NCD patient: JEAN PAUL MUGISHA, male, born 1985-03-15, from Kigali. The patient has a National ID (NID) and a Universal Patient Identifier (UPI).

### API Call

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -H "X-Correlation-ID: ncd-referral-demo-001" \
  -d '{
    "resourceType": "Patient",
    "name": [{
      "use": "official",
      "family": "MUGISHA",
      "given": ["JEAN", "PAUL"]
    }],
    "gender": "male",
    "birthDate": "1985-03-15",
    "identifier": [
      {"system": "NID", "value": "1198503150001234"},
      {"system": "UPI", "value": "040505-0001-1234"}
    ],
    "address": [{
      "use": "home",
      "city": "Kigali",
      "district": "Gasabo",
      "country": "Rwanda"
    }],
    "telecom": [{"system": "phone", "value": "+250788123456"}]
  }' | jq .
```

### What the Adaptor Does

1. **PatientIdentifierEnricher:**
   - Generates OpenMRS ID with LuhnMod30 check digit (e.g., `9AX7MDK2`)
   - Creates `use: "official"` identifier with type "OpenMRS ID"
   - Promotes NID identifier: adds `type.text: "NID"`, `type.coding[0].code: <uuid>`, removes `system`
   - Promotes UPI identifier: adds `type.text: "UPI"`, `type.coding[0].code: <uuid>`, removes `system`
   - Adds location extension to all identifiers
   - If NID/UPI identifier types don't exist in O3 → auto-creates them

2. **FhirElementIdEnricher:**
   - Adds UUID `id` to: name, identifier, address, telecom arrays

3. **RequiredFieldEnricher:** (no Patient-specific defaults needed)

4. **FhirReferenceResolver:** (no references to resolve for Patient)

### Expected Response

```json
{
  "timestamp": "2026-04-05T07:00:00.000Z",
  "totalEntries": 1,
  "succeeded": 1,
  "failed": 0,
  "results": [{
    "resourceType": "Patient",
    "resourceId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",  ← SAVE THIS
    "route": "fhir",
    "status": "created",
    "httpStatus": 201,
    "errorMessage": null
  }]
}
```

> **⚠️ Save `resourceId` as `PATIENT_UUID` for all subsequent steps.**

### Verify in O3

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Patient/${PATIENT_UUID}" | jq .
```

Check that identifiers have `type.text` values and no bare `system` fields.

---

## Step 2 — NCD Screening Encounter (SPICE → Adaptor)

**Scenario:** Jane Nurse conducts an NCD screening visit at Outpatient Clinic. This is a "Visit Note" encounter.

### API Call

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -H "X-Correlation-ID: ncd-referral-demo-001" \
  -d '{
    "resourceType": "Encounter",
    "meta": {
      "tag": [{"system": "http://fhir.openmrs.org/ext/encounter-tag", "code": "encounter", "display": "Encounter"}]
    },
    "status": "finished",
    "class": {
      "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
      "code": "AMB",
      "display": "Ambulatory"
    },
    "type": [{
      "coding": [{
        "system": "http://fhir.openmrs.org/code-system/encounter-type",
        "code": "d7151f82-c1f3-4152-a605-2f9ea7414a79",
        "display": "Visit Note"
      }]
    }],
    "subject": {
      "reference": "Patient/'"${PATIENT_UUID}"'",
      "type": "Patient"
    },
    "period": {"start": "2026-04-05T09:00:00+02:00"},
    "location": [{
      "location": {
        "reference": "Location/44c3efb0-2583-4c80-a79e-1f756a03c0a1",
        "display": "Outpatient Clinic"
      }
    }],
    "participant": [{
      "individual": {
        "reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649",
        "display": "Jane Nurse"
      }
    }]
  }' | jq .
```

### What the Adaptor Does

1. **FhirReferenceResolver:** Validates Patient/Practitioner/Location references (already UUIDs, no resolution needed)
2. **FhirElementIdEnricher:** Adds UUID `id` to: type, location, participant arrays
3. **RequiredFieldEnricher:** Ensures `period.start` and `status` are present (they are)

### Expected Response

```json
{
  "results": [{
    "resourceType": "Encounter",
    "resourceId": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",  ← SAVE THIS
    "route": "fhir",
    "status": "created",
    "httpStatus": 201
  }]
}
```

> **⚠️ Save `resourceId` as `ENCOUNTER_UUID`.**

---

## Step 3 — NCD Observations (SPICE → Adaptor)

**Scenario:** Jane Nurse records vitals and a point-of-care blood glucose test.

### 3a. Systolic Blood Pressure — 155 mmHg (elevated)

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -d '{
    "resourceType": "Observation",
    "status": "final",
    "code": {
      "coding": [{
        "system": "http://ciel.org",
        "code": "5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "display": "Systolic blood pressure"
      }]
    },
    "subject": {"reference": "Patient/'"${PATIENT_UUID}"'"},
    "encounter": {"reference": "Encounter/'"${ENCOUNTER_UUID}"'"},
    "effectiveDateTime": "2026-04-05T09:15:00+02:00",
    "valueQuantity": {
      "value": 155,
      "unit": "mmHg",
      "system": "http://unitsofmeasure.org",
      "code": "mm[Hg]"
    }
  }' | jq .results[0].resourceId
```

> **Note:** LOINC 8480-6 (Systolic BP) is NOT mapped in this O3 instance. We use `http://ciel.org` with the CIEL concept UUID directly. The ConceptResolver recognizes `http://ciel.org` as native and passes the code through.

### 3b. Diastolic Blood Pressure — 95 mmHg

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -d '{
    "resourceType": "Observation",
    "status": "final",
    "code": {
      "coding": [{
        "system": "http://ciel.org",
        "code": "5086AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "display": "Diastolic blood pressure"
      }]
    },
    "subject": {"reference": "Patient/'"${PATIENT_UUID}"'"},
    "encounter": {"reference": "Encounter/'"${ENCOUNTER_UUID}"'"},
    "effectiveDateTime": "2026-04-05T09:15:00+02:00",
    "valueQuantity": {
      "value": 95,
      "unit": "mmHg",
      "system": "http://unitsofmeasure.org",
      "code": "mm[Hg]"
    }
  }' | jq .results[0].resourceId
```

### 3c. Fasting Blood Glucose — 310 mg/dL (critically high)

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -d '{
    "resourceType": "Observation",
    "status": "final",
    "code": {
      "coding": [{
        "system": "http://loinc.org",
        "code": "1558-6",
        "display": "Fasting glucose"
      }]
    },
    "subject": {"reference": "Patient/'"${PATIENT_UUID}"'"},
    "encounter": {"reference": "Encounter/'"${ENCOUNTER_UUID}"'"},
    "effectiveDateTime": "2026-04-05T09:20:00+02:00",
    "valueQuantity": {
      "value": 310,
      "unit": "mg/dL",
      "system": "http://unitsofmeasure.org",
      "code": "mg/dL"
    }
  }' | jq .results[0].resourceId
```

> **Adaptor magic:** LOINC 1558-6 IS mapped in this O3 instance. The ConceptResolver calls `GET /concept?source=LOINC&code=1558-6` → gets `160912AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` and injects it as an OpenMRS-native coding at position 0.

### What the Adaptor Does for Each Observation

1. **ConceptResolver:** Resolves `code.coding` to OpenMRS concept UUID
2. **RequiredFieldEnricher:** Ensures `effectiveDateTime` and `status` present
3. **FhirElementIdEnricher:** Adds UUID `id` to coding, category arrays i present
4. **FhirReferenceResolver:** Validates Patient and Encounter references

---

## Step 4 — Diagnosis: Type 2 Diabetes (SPICE → Adaptor)

**Scenario:** Based on the extremely elevated fasting glucose (310 mg/dL), the clinician records a diagnosis of Type 2 Diabetes Mellitus.

### API Call

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -d '{
    "resourceType": "Condition",
    "clinicalStatus": {
      "coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]
    },
    "verificationStatus": {
      "coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-ver-status", "code": "confirmed"}]
    },
    "code": {
      "coding": [{
        "system": "http://ciel.org",
        "code": "142473AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "display": "Diabetes mellitus, type 2"
      }]
    },
    "subject": {"reference": "Patient/'"${PATIENT_UUID}"'"},
    "encounter": {"reference": "Encounter/'"${ENCOUNTER_UUID}"'"},
    "onsetDateTime": "2026-04-05T09:00:00+02:00",
    "recorder": {
      "reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649",
      "display": "Jane Nurse"
    }
  }' | jq .
```

> **Why CIEL and not SNOMED?** SNOMED 44054006 (Diabetes mellitus type 2) maps to the wrong concept in this O3 instance (`156162` = erectile dysfunction with T2DM). Always verify concept mappings before using SNOMED codes.

### Expected Response

```json
{
  "results": [{
    "resourceType": "Condition",
    "resourceId": "cccccccc-cccc-cccc-cccc-cccccccccccc",  ← SAVE THIS
    "route": "fhir",
    "status": "created",
    "httpStatus": 201
  }]
}
```

> **⚠️ Save `resourceId` as `CONDITION_UUID`.**

---

## Step 5 — Lab Referral: ServiceRequest + Task (SPICE → Adaptor)

**Scenario:** The clinician orders an HbA1c test and creates a task to track the referral to the lab.

### 5a. Create ServiceRequest (HbA1c Lab Order)

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -d '{
    "resourceType": "ServiceRequest",
    "status": "active",
    "intent": "order",
    "priority": "urgent",
    "code": {
      "coding": [{
        "code": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "display": "Glycosylated hemoglobin A measurement (HbA1c)"
      }]
    },
    "subject": {
      "reference": "Patient/'"${PATIENT_UUID}"'",
      "display": "MUGISHA JEAN PAUL"
    },
    "encounter": {"reference": "Encounter/'"${ENCOUNTER_UUID}"'"},
    "requester": {
      "reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649",
      "display": "Jane Nurse"
    },
    "note": [{"text": "Fasting glucose 310 mg/dL. Urgent HbA1c needed to confirm uncontrolled diabetes."}]
  }' | jq .
```

### What the Adaptor Does (REST Route)

ServiceRequest is **NOT supported** for creation via O3 FHIR. The adaptor:

1. Detects `ServiceRequest` → force-routes to **REST** path
2. **FhirToRestTransformer** converts FHIR → OpenMRS REST:
   ```json
   {
     "type": "testorder",
     "careSetting": "OUTPATIENT",
     "concept": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
     "patient": "<patient-uuid>",
     "encounter": "<encounter-uuid>",
     "orderer": "<practitioner-uuid>",
     "action": "NEW",
     "urgency": "STAT"
   }
   ```
3. POSTs to `POST /ws/rest/v1/order`

### Expected Response

```json
{
  "results": [{
    "resourceType": "ServiceRequest",
    "resourceId": "ssssssss-ssss-ssss-ssss-ssssssssssss",
    "route": "rest",         ← Note: REST, not FHIR
    "status": "created",
    "httpStatus": 201
  }]
}
```

> **⚠️ Save `resourceId` as `SERVICE_REQUEST_UUID`.**

### 5b. Create Task (Referral Tracker)

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: spice" \
  -d '{
    "resourceType": "Task",
    "status": "requested",
    "intent": "order",
    "priority": "urgent",
    "code": {"text": "HbA1c Lab Referral"},
    "description": "Refer to Inpatient Ward lab for HbA1c test. Fasting glucose 310 mg/dL. Suspected uncontrolled Type 2 DM.",
    "for": {
      "reference": "Patient/'"${PATIENT_UUID}"'",
      "display": "MUGISHA JEAN PAUL"
    },
    "basedOn": [{
      "reference": "ServiceRequest/'"${SERVICE_REQUEST_UUID}"'",
      "type": "ServiceRequest"
    }],
    "encounter": {"reference": "Encounter/'"${ENCOUNTER_UUID}"'"},
    "requester": {
      "reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649",
      "display": "Jane Nurse"
    },
    "owner": {
      "reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d",
      "display": "June Technician"
    },
    "authoredOn": "2026-04-05T10:00:00+02:00"
  }' | jq .
```

> **⚠️ Save `resourceId` as `TASK_UUID`.**

### Verify Task State

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task/${TASK_UUID}" \
  | jq '{status, intent, basedOn: .basedOn[0].reference, owner: .owner.display}'
```

Expected: `{"status": "requested", "intent": "order", "basedOn": "ServiceRequest/...", "owner": "June Technician"}`

---

## Step 6 — Lab Accepts Task (Direct O3 FHIR)

**Scenario:** Lab technician June Technician sees the incoming task and accepts it.

> From this point, the lab system interacts **directly** with O3 FHIR (not through the adaptor).

### API Call

```bash
curl -s -X PUT -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task/${TASK_UUID}" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Task",
    "id": "'"${TASK_UUID}"'",
    "status": "accepted",
    "intent": "order",
    "basedOn": [{"reference": "ServiceRequest/'"${SERVICE_REQUEST_UUID}"'", "type": "ServiceRequest"}],
    "for": {"reference": "Patient/'"${PATIENT_UUID}"'", "display": "MUGISHA JEAN PAUL"},
    "owner": {"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d", "display": "June Technician"},
    "authoredOn": "2026-04-05T10:00:00+02:00",
    "lastModified": "2026-04-05T11:00:00+02:00"
  }' | jq '{status}'
```

**Expected:** `{"status": "accepted"}`

---

## Step 7 — Lab Records Results (via Adaptor)

**Scenario:** June Technician runs the HbA1c assay, records result of 11.2% (critical high), creates a DiagnosticReport.

> **Why through the adaptor?** The VisitManager automatically finds (or creates) an active Facility Visit for the patient and sets the `partOf` reference on the Encounter. Without this, the encounter would be invisible in the O3 Visits UI.

### 7a. Create Lab Encounter

```bash
LAB_RESPONSE=$(curl -s -X POST \
  "http://localhost:8083/api/v1/fhir" \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: lab-system" \
  -d '{
    "resourceType": "Encounter",
    "status": "finished",
    "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB", "display": "Ambulatory"},
    "type": [{"coding": [{"system": "http://fhir.openmrs.org/code-system/encounter-type", "code": "3596fafb-6f6f-4396-8c87-6e63a0f1bd71", "display": "Lab Results"}]}],
    "subject": {"reference": "Patient/'"${PATIENT_UUID}"'"},
    "period": {"start": "2026-04-05T14:00:00+02:00", "end": "2026-04-05T14:30:00+02:00"},
    "location": [{"location": {"reference": "Location/ba685651-ed3b-4e63-9b35-78893060758a", "display": "Inpatient Ward"}}],
    "participant": [{"individual": {"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d", "display": "June Technician"}}]
  }')

LAB_ENC=$(echo "${LAB_RESPONSE}" | jq -r '.results[0].resourceId')
echo "Lab Encounter UUID: ${LAB_ENC}"
```

> **Note:** The adaptor returns `{"results": [{"resourceId": "...", "status": "created", ...}]}`. The `resourceId` is the OpenMRS UUID.

### 7b. Record HbA1c Observation (11.2% — Critical High)

```bash
OBS_RESPONSE=$(curl -s -X POST \
  "http://localhost:8083/api/v1/fhir" \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: lab-system" \
  -d '{
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "laboratory", "display": "Laboratory"}]}],
    "code": {"coding": [{"code": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "display": "Glycosylated hemoglobin A measurement"}]},
    "subject": {"reference": "Patient/'"${PATIENT_UUID}"'"},
    "encounter": {"reference": "Encounter/'"${LAB_ENC}"'"},
    "effectiveDateTime": "2026-04-05T14:15:00+02:00",
    "issued": "2026-04-05T14:25:00+02:00",
    "valueQuantity": {"value": 11.2, "unit": "%", "system": "http://unitsofmeasure.org", "code": "%"},
    "interpretation": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation", "code": "HH", "display": "Critical high"}]}],
    "performer": [{"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d", "display": "June Technician"}]
  }')

HBA1C_OBS=$(echo "${OBS_RESPONSE}" | jq -r '.results[0].resourceId')
echo "HbA1c Observation UUID: ${HBA1C_OBS}"
```

> **Clinical context:** HbA1c 11.2% is critically elevated (normal < 5.7%, prediabetes 5.7–6.4%, diabetes ≥ 6.5%). This confirms uncontrolled diabetes.

### 7c. Create DiagnosticReport

```bash
DR_RESPONSE=$(curl -s -X POST \
  "http://localhost:8083/api/v1/fhir" \
  -H "Content-Type: application/fhir+json" \
  -H "X-Source-System: lab-system" \
  -d '{
    "resourceType": "DiagnosticReport",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0074", "code": "LAB", "display": "Laboratory"}]}],
    "code": {
      "coding": [{"code": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "display": "Glycosylated hemoglobin A measurement"}],
      "text": "HbA1c Test Report"
    },
    "subject": {"reference": "Patient/'"${PATIENT_UUID}"'"},
    "encounter": {"reference": "Encounter/'"${LAB_ENC}"'"},
    "effectiveDateTime": "2026-04-05T14:25:00+02:00",
    "issued": "2026-04-05T14:30:00+02:00",
    "result": [{"reference": "Observation/'"${HBA1C_OBS}"'", "display": "HbA1c = 11.2%"}],
    "conclusion": "HbA1c 11.2% — critically elevated. Confirms uncontrolled Type 2 diabetes. Recommend insulin initiation and dietary counseling."
  }')

DIAG_REPORT=$(echo "${DR_RESPONSE}" | jq -r '.results[0].resourceId')
echo "DiagnosticReport UUID: ${DIAG_REPORT}"
```

---

## Step 8 — Complete Task with Results (Direct O3 FHIR)

**Scenario:** Lab technician marks the task as completed and links the DiagnosticReport and Observation as outputs.

### API Call

```bash
curl -s -X PUT -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task/${TASK_UUID}" \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Task",
    "id": "'"${TASK_UUID}"'",
    "status": "completed",
    "intent": "order",
    "basedOn": [{"reference": "ServiceRequest/'"${SERVICE_REQUEST_UUID}"'", "type": "ServiceRequest"}],
    "for": {"reference": "Patient/'"${PATIENT_UUID}"'", "display": "MUGISHA JEAN PAUL"},
    "owner": {"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d", "display": "June Technician"},
    "authoredOn": "2026-04-05T10:00:00+02:00",
    "lastModified": "2026-04-05T14:30:00+02:00",
    "executionPeriod": {
      "start": "2026-04-05T14:00:00+02:00",
      "end": "2026-04-05T14:30:00+02:00"
    },
    "output": [
      {
        "type": {
          "coding": [{"code": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "display": "HbA1c Result"}],
          "text": "HbA1c Result"
        },
        "valueReference": {
          "reference": "Observation/'"${HBA1C_OBS}"'",
          "display": "HbA1c 11.2%"
        }
      }
    ]
  }' | jq '{status, output: [.output[].valueReference.display]}'
```

> **Note:** `output[].type` must contain a `coding` with a valid OpenMRS concept UUID. The `text`-only format causes `FhirTaskOutput.type` null errors. Here we use the HbA1c concept (`159644AAAA...`) to categorize the output.

**Expected:**
```json
{
  "status": "completed",
  "output": [
    "HbA1c 11.2%"
  ]
}
```

---

## Step 9 — Verification Queries

### 9a. Full Task with Output References

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task/${TASK_UUID}" \
  | jq '{status, basedOn: .basedOn[0].reference, output: [.output[].valueReference]}'
```

### 9b. Patient's Complete Encounter History

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Encounter?patient=${PATIENT_UUID}&_sort=-date" \
  | jq '.entry[] | {type: .resource.type[0].coding[0].display, location: .resource.location[0].location.display, id: .resource.id}'
```

**Expected:** Two encounters — "Visit Note" at Outpatient Clinic and "Lab Results" at Inpatient Ward.

### 9c. Patient's Conditions

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Condition?patient=${PATIENT_UUID}" \
  | jq '.entry[].resource | {code: .code.text, status: .clinicalStatus.coding[0].code}'
```

**Expected:** `{"code": "Diabetes mellitus, type 2", "status": "active"}`

### 9d. Patient's Lab Orders

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/ServiceRequest?patient=${PATIENT_UUID}" \
  | jq '.entry[].resource | {code: .code.coding[0].display, status, intent}'
```

### 9e. DiagnosticReport with Conclusion

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/DiagnosticReport/${DIAG_REPORT}" \
  | jq '{status, conclusion, result: [.result[].display]}'
```

---

## Summary — Resource Routing

| Step | Resource | Route | Endpoint |
|---|---|---|---|
| 1 | Patient | **FHIR** | Adaptor → O3 FHIR |
| 2 | Encounter | **FHIR** | Adaptor → O3 FHIR |
| 3a–c | Observation (×3) | **FHIR** | Adaptor → O3 FHIR |
| 4 | Condition | **FHIR** | Adaptor → O3 FHIR |
| 5a | ServiceRequest | **REST** | Adaptor → O3 REST (`/order`) |
| 5b | Task | **FHIR** | Adaptor → O3 FHIR |
| 6 | Task (update) | FHIR | Direct O3 FHIR |
| 7a | Encounter (lab) | FHIR | Adaptor → O3 FHIR (+ Visit auto-link) |
| 7b | Observation (HbA1c) | FHIR | Adaptor → O3 FHIR |
| 7c | DiagnosticReport | FHIR | Adaptor → O3 FHIR |
| 8 | Task (complete) | FHIR | Direct O3 FHIR |

---

## Concept Reference

| Clinical Concept | System | Code | CIEL UUID |
|---|---|---|---|
| Systolic BP | CIEL | 5085 | `5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| Diastolic BP | CIEL | 5086 | `5086AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| Fasting Glucose | LOINC | 1558-6 | `160912AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| HbA1c | CIEL | 159644 | `159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |
| Diabetes mellitus, type 2 | CIEL | 142473 | `142473AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` |

> **⚠️ LOINC 8480-6 (Systolic BP) and 8462-4 (Diastolic BP) are NOT mapped in this O3 instance. Use CIEL codes directly.**

> **⚠️ SNOMED 44054006 (T2DM) maps to the wrong concept. Use CIEL 142473 directly.**

---

## Postman Collection

Import [CCE-NCD-Referral-E2E.postman_collection.json](postman/CCE-NCD-Referral-E2E.postman_collection.json) for a ready-to-run version with automatic UUID extraction via test scripts.
