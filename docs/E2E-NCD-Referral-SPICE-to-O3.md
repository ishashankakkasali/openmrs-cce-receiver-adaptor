# E2E NCD Referral Workflow — SPICE → CCE Adaptor → OpenMRS O3

> **Testable end-to-end flow** using the actual OpenMRS O3 instance at `localhost:9096`  
> with the CCE Receiver Adaptor at `localhost:8083`

---

## Scenario Overview

| | Details |
|---|---|
| **Patient** | NSENGIMANA PATRICK (existing in O3) |
| **Patient UUID** | `1a09907b-f084-483f-b8b3-2dc4347091d8` |
| **Patient IDs** | OpenMRS ID: `19KWTLU`, NID: `1192880005226004`, UPI: `040326-0004-7890` |
| **NCD** | Type 2 Diabetes — NCD assessment from SPICE |
| **Origin Location** | Outpatient Clinic (`44c3efb0-2583-4c80-a79e-1f756a03c0a1`) |
| **Referral Location** | Inpatient Ward (`ba685651-ed3b-4e63-9b35-78893060758a`) — for HbA1c lab |
| **Practitioner (Requester)** | Jane Nurse (`1fee2f21-82f3-4aab-8d87-f1cf19034649`) |
| **Practitioner (Performer)** | June Technician (`92416875-5fe2-4d3b-8531-e2c8c846a69d`) |
| **Encounter Type** | Visit Note (`d7151f82-c1f3-4152-a605-2f9ea7414a79`) |
| **Lab Results Encounter Type** | Lab Results (`3596fafb-6f6f-4396-8c87-6e63a0f1bd71`) |

### Key O3 Facts for This Workflow

- **ServiceRequest** in O3 is **read-only** via FHIR (`read`, `search-type` only)
- **Task** in O3 supports **full CRUD** via FHIR (`create`, `read`, `update`, `delete`, `patch`, `search-type`)
- The CCE Adaptor creates ServiceRequests via the **REST API** (`POST /order` as TestOrder) using `FhirToRestTransformer`
- Both locations are in the **same O3 instance** — no RHIE needed
- Task links ServiceRequest to its fulfillment and carries status across encounters

---

## Prerequisites

```bash
# 1. O3 containers running
docker ps --format "{{.Names}} {{.Status}}" | grep o3

# 2. CCE Adaptor running
curl -s http://localhost:8083/actuator/health | head -5

# 3. Patient exists
curl -s -u admin:Admin123 \
  'http://localhost:9096/openmrs/ws/fhir2/R4/Patient?name=NSENGIMANA' \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('total',0),'patients found')"
```

---

## Step 1: SPICE NCD Assessment — Create Patient + Encounter + Observations

SPICE performs an NCD screening and sends the data to the CCE Adaptor. The adaptor creates everything in OpenMRS O3.

### 1a. Create the NCD Encounter (at Outpatient Clinic)

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H 'Content-Type: application/fhir+json' \
  -d '{
  "resourceType": "Encounter",
  "meta": {
    "tag": [{"system": "http://fhir.openmrs.org/ext/encounter-tag",
             "code": "encounter", "display": "Encounter"}]
  },
  "status": "finished",
  "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
            "code": "AMB", "display": "Ambulatory"},
  "type": [{"coding": [{"system": "http://fhir.openmrs.org/code-system/encounter-type",
                         "code": "d7151f82-c1f3-4152-a605-2f9ea7414a79",
                         "display": "Visit Note"}]}],
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8",
              "type": "Patient",
              "display": "NSENGIMANA PATRICK"},
  "period": {"start": "2026-04-03T09:00:00+02:00"},
  "location": [{"location": {"reference": "Location/44c3efb0-2583-4c80-a79e-1f756a03c0a1",
                              "display": "Outpatient Clinic"}}],
  "participant": [{"individual": {"reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649",
                                  "display": "Jane Nurse"}}]
}'
```

> **Save the returned Encounter UUID** — you'll need it for all subsequent resources.  
> Example: `ENCOUNTER_UUID=<returned-uuid>`

### 1b. Create NCD Observations (Vitals + Lab)

**Systolic Blood Pressure:**
```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H 'Content-Type: application/fhir+json' \
  -d '{
  "resourceType": "Observation",
  "status": "final",
  "code": {
    "coding": [{"system": "http://loinc.org", "code": "8480-6",
                "display": "Systolic blood pressure"}]
  },
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8"},
  "encounter": {"reference": "Encounter/<ENCOUNTER_UUID>"},
  "effectiveDateTime": "2026-04-03T09:15:00+02:00",
  "valueQuantity": {"value": 155, "unit": "mmHg",
                     "system": "http://unitsofmeasure.org", "code": "mm[Hg]"}
}'
```

**Fasting Blood Glucose (high — triggers referral):**
```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H 'Content-Type: application/fhir+json' \
  -d '{
  "resourceType": "Observation",
  "status": "final",
  "code": {
    "coding": [{"system": "http://loinc.org", "code": "1558-6",
                "display": "Fasting glucose"}]
  },
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8"},
  "encounter": {"reference": "Encounter/<ENCOUNTER_UUID>"},
  "effectiveDateTime": "2026-04-03T09:20:00+02:00",
  "valueQuantity": {"value": 310, "unit": "mg/dL",
                     "system": "http://unitsofmeasure.org", "code": "mg/dL"}
}'
```

### 1c. Create Condition (Diabetes diagnosis)

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H 'Content-Type: application/fhir+json' \
  -d '{
  "resourceType": "Condition",
  "clinicalStatus": {
    "coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                "code": "active"}]
  },
  "verificationStatus": {
    "coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-ver-status",
                "code": "confirmed"}]
  },
  "code": {
    "coding": [{"system": "http://snomed.info/sct", "code": "44054006",
                "display": "Type 2 diabetes mellitus"}]
  },
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8"},
  "encounter": {"reference": "Encounter/<ENCOUNTER_UUID>"},
  "onsetDateTime": "2026-04-03T09:00:00+02:00",
  "recorder": {"reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649"}
}'
```

### Verify Step 1 in O3 UI

1. Open http://localhost:9096/openmrs/spa/patient/1a09907b-f084-483f-b8b3-2dc4347091d8/chart
2. You should see the Visit Note encounter with:
   - BP: 155 mmHg (systolic)
   - Fasting Glucose: 310 mg/dL
   - Condition: Type 2 Diabetes

---

## Step 2: SPICE Creates Referral — ServiceRequest + Task via Adaptor

The NCD clinician sees dangerously high glucose (310 mg/dL) and creates a referral for HbA1c testing at the Inpatient Ward's lab.

### 2a. Create ServiceRequest (Referral for HbA1c Test)

ServiceRequest is read-only via FHIR in O3, so the adaptor routes it through the **REST API** as a TestOrder using `FhirToRestTransformer`.

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H 'Content-Type: application/fhir+json' \
  -d '{
  "resourceType": "ServiceRequest",
  "status": "active",
  "intent": "order",
  "priority": "urgent",
  "code": {
    "coding": [{"code": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "display": "Glycosylated hemoglobin A measurement (HbA1c)"}]
  },
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8",
              "display": "NSENGIMANA PATRICK"},
  "encounter": {"reference": "Encounter/<ENCOUNTER_UUID>"},
  "requester": {"reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649",
                "display": "Jane Nurse"},
  "note": [{"text": "Fasting glucose 310 mg/dL. Urgent HbA1c needed to confirm uncontrolled diabetes."}]
}'
```

> **Save the returned ServiceRequest UUID** — the REST API returns the Order UUID.  
> Example: `SR_UUID=<returned-uuid>`

### Verify ServiceRequest was created:

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/ServiceRequest?patient=1a09907b-f084-483f-b8b3-2dc4347091d8&_sort=-_lastUpdated&_count=1" \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(f'Total ServiceRequests: {d.get(\"total\",0)}')
for e in d.get('entry',[]):
    sr=e['resource']
    print(f'  id:     {sr[\"id\"]}')
    print(f'  status: {sr[\"status\"]}')
    print(f'  code:   {sr.get(\"code\",{}).get(\"text\",\"?\")}')
    print(f'  intent: {sr[\"intent\"]}')
"
```

### 2b. Create Task (to track referral fulfillment)

Task has full FHIR CRUD support in O3, so this goes directly via FHIR.

```bash
curl -s -X POST http://localhost:8083/api/v1/fhir \
  -H 'Content-Type: application/fhir+json' \
  -d '{
  "resourceType": "Task",
  "status": "requested",
  "intent": "order",
  "priority": "urgent",
  "code": {"text": "HbA1c Lab Referral"},
  "description": "Refer to Inpatient Ward lab for HbA1c test. Fasting glucose 310 mg/dL. Suspected uncontrolled Type 2 DM.",
  "for": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8",
          "display": "NSENGIMANA PATRICK"},
  "basedOn": [{"reference": "ServiceRequest/<SR_UUID>",
               "type": "ServiceRequest"}],
  "encounter": {"reference": "Encounter/<ENCOUNTER_UUID>"},
  "requester": {"reference": "Practitioner/1fee2f21-82f3-4aab-8d87-f1cf19034649",
                "display": "Jane Nurse"},
  "owner": {"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d",
            "display": "June Technician"},
  "authoredOn": "2026-04-03T10:00:00+02:00"
}'
```

> **Save the returned Task UUID** — you'll update it later.  
> Example: `TASK_UUID=<returned-uuid>`

### Verify the Task:

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task/<TASK_UUID>" \
  | python3 -c "
import sys,json
t=json.load(sys.stdin)
print(f'Task id:     {t[\"id\"]}')
print(f'status:      {t[\"status\"]}')
print(f'intent:      {t[\"intent\"]}')
print(f'basedOn:     {t.get(\"basedOn\",[])}')
print(f'description: {t.get(\"description\",\"?\")}')
print(f'owner:       {t.get(\"owner\",{}).get(\"display\",\"?\")}')
"
```

### State After Step 2

```
Outpatient Clinic (Encounter: Visit Note)
  ├── Observation: Systolic BP = 155 mmHg
  ├── Observation: Fasting Glucose = 310 mg/dL
  ├── Condition: Type 2 Diabetes (confirmed)
  ├── ServiceRequest: HbA1c Test (status: active)
  └── Task: HbA1c Lab Referral (status: requested)
              basedOn → ServiceRequest
              owner → June Technician (Inpatient Ward lab)
```

---

## Step 3: Lab Technician at Inpatient Ward — Accepts and Does the Test

Now the Lab Technician (June Technician) at the Inpatient Ward sees the Task, accepts it, performs the HbA1c test, and records results.

### 3a. Update Task to "accepted"

This is done **directly in OpenMRS** (not via SPICE/Adaptor) — the lab tech updates the task status.

```bash
curl -s -X PUT -u admin:Admin123 \
  -H 'Content-Type: application/fhir+json' \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task/<TASK_UUID>" \
  -d '{
  "resourceType": "Task",
  "id": "<TASK_UUID>",
  "status": "accepted",
  "intent": "order",
  "basedOn": [{"reference": "ServiceRequest/<SR_UUID>",
               "type": "ServiceRequest"}],
  "for": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8",
          "display": "NSENGIMANA PATRICK"},
  "owner": {"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d",
            "display": "June Technician"},
  "authoredOn": "2026-04-03T10:00:00+02:00",
  "lastModified": "2026-04-03T11:00:00+02:00"
}'
```

### 3b. Create Lab Results Encounter (at Inpatient Ward)

```bash
curl -s -X POST -u admin:Admin123 \
  -H 'Content-Type: application/fhir+json' \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Encounter" \
  -d '{
  "resourceType": "Encounter",
  "status": "finished",
  "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
            "code": "AMB", "display": "Ambulatory"},
  "type": [{"coding": [{"system": "http://fhir.openmrs.org/code-system/encounter-type",
                         "code": "3596fafb-6f6f-4396-8c87-6e63a0f1bd71",
                         "display": "Lab Results"}]}],
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8",
              "display": "NSENGIMANA PATRICK"},
  "period": {"start": "2026-04-03T14:00:00+02:00",
             "end": "2026-04-03T14:30:00+02:00"},
  "location": [{"location": {"reference": "Location/ba685651-ed3b-4e63-9b35-78893060758a",
                              "display": "Inpatient Ward"}}],
  "participant": [{"individual": {"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d",
                                  "display": "June Technician"}}]
}'
```

> **Save the returned Lab Encounter UUID.**  
> Example: `LAB_ENC_UUID=<returned-uuid>`

### 3c. Record HbA1c Result (Observation)

```bash
curl -s -X POST -u admin:Admin123 \
  -H 'Content-Type: application/fhir+json' \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Observation" \
  -d '{
  "resourceType": "Observation",
  "status": "final",
  "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category",
                             "code": "laboratory", "display": "Laboratory"}]}],
  "code": {
    "coding": [{"code": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "display": "Glycosylated hemoglobin A measurement"}]
  },
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8"},
  "encounter": {"reference": "Encounter/<LAB_ENC_UUID>"},
  "effectiveDateTime": "2026-04-03T14:15:00+02:00",
  "issued": "2026-04-03T14:25:00+02:00",
  "valueQuantity": {"value": 11.2, "unit": "%",
                     "system": "http://unitsofmeasure.org", "code": "%"},
  "interpretation": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation",
                                   "code": "HH", "display": "Critical high"}]}],
  "performer": [{"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d",
                 "display": "June Technician"}]
}'
```

> **Save the HbA1c Observation UUID.**  
> Example: `HBAC1_OBS_UUID=<returned-uuid>`

### 3d. Create DiagnosticReport with conclusion

```bash
curl -s -X POST -u admin:Admin123 \
  -H 'Content-Type: application/fhir+json' \
  "http://localhost:9096/openmrs/ws/fhir2/R4/DiagnosticReport" \
  -d '{
  "resourceType": "DiagnosticReport",
  "status": "final",
  "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0074",
                             "code": "LAB", "display": "Laboratory"}]}],
  "code": {
    "coding": [{"code": "159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "display": "Glycosylated hemoglobin A measurement"}],
    "text": "HbA1c Test Report"
  },
  "subject": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8"},
  "encounter": {"reference": "Encounter/<LAB_ENC_UUID>"},
  "effectiveDateTime": "2026-04-03T14:25:00+02:00",
  "issued": "2026-04-03T14:30:00+02:00",
  "result": [{"reference": "Observation/<HBAC1_OBS_UUID>",
              "display": "HbA1c = 11.2%"}],
  "conclusion": "HbA1c 11.2% — critically elevated. Confirms uncontrolled Type 2 diabetes. Recommend insulin initiation and dietary counseling."
}'
```

> **Save the DiagnosticReport UUID.**  
> Example: `DIAG_UUID=<returned-uuid>`

---

## Step 4: Lab Technician Completes the Task — Results Link Back

The lab tech marks the Task as **completed** and attaches the output references. This is the critical step that links test results back to the original referral.

### 4a. Update Task to "completed" with output references

```bash
curl -s -X PUT -u admin:Admin123 \
  -H 'Content-Type: application/fhir+json' \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task/<TASK_UUID>" \
  -d '{
  "resourceType": "Task",
  "id": "<TASK_UUID>",
  "status": "completed",
  "intent": "order",
  "basedOn": [{"reference": "ServiceRequest/<SR_UUID>",
               "type": "ServiceRequest"}],
  "for": {"reference": "Patient/1a09907b-f084-483f-b8b3-2dc4347091d8",
          "display": "NSENGIMANA PATRICK"},
  "owner": {"reference": "Practitioner/92416875-5fe2-4d3b-8531-e2c8c846a69d",
            "display": "June Technician"},
  "authoredOn": "2026-04-03T10:00:00+02:00",
  "lastModified": "2026-04-03T14:30:00+02:00",
  "executionPeriod": {
    "start": "2026-04-03T14:00:00+02:00",
    "end": "2026-04-03T14:30:00+02:00"
  },
  "output": [
    {
      "type": {"text": "DiagnosticReport"},
      "valueReference": {"reference": "DiagnosticReport/<DIAG_UUID>",
                          "display": "HbA1c = 11.2% — Critically elevated"}
    },
    {
      "type": {"text": "Observation"},
      "valueReference": {"reference": "Observation/<HBAC1_OBS_UUID>",
                          "display": "HbA1c 11.2%"}
    }
  ]
}'
```

---

## Step 5: NCD Clinician at Outpatient Clinic — Views Results

The NCD clinician (Jane Nurse) back at Outpatient Clinic queries the Task to see if the referral was completed and what the results were.

### 5a. Query Task by ServiceRequest

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Task?based-on=ServiceRequest/<SR_UUID>" \
  | python3 -c "
import sys  , json
d = json.load(sys.stdin)
print(f'Total Tasks: {d.get(\"total\",0)}')
for e in d.get('entry', []):
    t = e['resource']
    print(f'  Task id:     {t[\"id\"]}')
    print(f'  status:      {t[\"status\"]}')
    print(f'  lastModified: {t.get(\"lastModified\",\"?\")}')
    for out in t.get('output', []):
        print(f'  output:      {out[\"type\"][\"text\"]} → {out[\"valueReference\"][\"display\"]}')
        print(f'               ref: {out[\"valueReference\"][\"reference\"]}')
"
```

**Expected output:**
```
Total Tasks: 1
  Task id:     <TASK_UUID>
  status:      completed
  output:      DiagnosticReport → HbA1c = 11.2% — Critically elevated
               ref: DiagnosticReport/<DIAG_UUID>
  output:      Observation → HbA1c 11.2%
               ref: Observation/<HBAC1_OBS_UUID>
```

### 5b. Fetch the DiagnosticReport

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/DiagnosticReport/<DIAG_UUID>" \
  | python3 -c "
import sys, json
dr = json.load(sys.stdin)
print(f'DiagnosticReport id: {dr[\"id\"]}')
print(f'status:              {dr[\"status\"]}')
print(f'code:                {dr.get(\"code\",{}).get(\"text\",\"?\")}')
print(f'conclusion:          {dr.get(\"conclusion\",\"?\")}')
for r in dr.get('result',[]):
    print(f'result ref:          {r[\"reference\"]}')
"
```

### 5c. Fetch the HbA1c Observation

```bash
curl -s -u admin:Admin123 \
  "http://localhost:9096/openmrs/ws/fhir2/R4/Observation/<HBAC1_OBS_UUID>" \
  | python3 -c "
import sys, json
obs = json.load(sys.stdin)
print(f'Observation id: {obs[\"id\"]}')
print(f'status:         {obs[\"status\"]}')
print(f'code:           {obs.get(\"code\",{}).get(\"coding\",[{}])[0].get(\"display\",\"?\")}')
vq = obs.get('valueQuantity', {})
print(f'value:          {vq.get(\"value\")} {vq.get(\"unit\")}')
interp = obs.get('interpretation',[{}])[0].get('coding',[{}])[0]
print(f'interpretation: {interp.get(\"code\",\"?\")} ({interp.get(\"display\",\"?\")})')
"
```

### 5d. View in O3 UI

1. Open http://localhost:9096/openmrs/spa/patient/1a09907b-f084-483f-b8b3-2dc4347091d8/chart
2. You should see **two encounters**:
   - **Visit Note** (Outpatient Clinic) — the original NCD assessment with BP, Glucose, Diabetes condition
   - **Lab Results** (Inpatient Ward) — HbA1c = 11.2% with DiagnosticReport

Both encounters are visible on the **same patient's chart** because they are in the same OpenMRS instance.

---

## How Status Reflects — Complete Data Chain

```
┌─────────── Outpatient Clinic ────────────┐     ┌──────── Inpatient Ward ────────┐
│                                          │     │                                │
│  ENCOUNTER: Visit Note                   │     │  ENCOUNTER: Lab Results         │
│  (Jane Nurse, 09:00)                     │     │  (June Technician, 14:00)       │
│    ├── Obs: Systolic BP = 155 mmHg       │     │    ├── Obs: HbA1c = 11.2%       │
│    ├── Obs: Fasting Glucose = 310 mg/dL  │     │    └── DiagnosticReport         │
│    ├── Condition: Type 2 Diabetes        │     │         conclusion: "Critically │
│    │                                     │     │         elevated, insulin       │
│    ├── ServiceRequest: HbA1c Test ◄──────┼─────┼── basedOn                      │
│    │     status: active                  │     │                                │
│    │                                     │     │                                │
│    └── Task ◄────────────────────────────┼─────┼── UPDATED by Lab Tech          │
│          status: completed               │     │     output:                     │
│          basedOn: ServiceRequest          │     │       → DiagnosticReport        │
│          output:                         │     │       → Observation (HbA1c)     │
│            → DiagnosticReport            │     │                                │
│            → Observation (HbA1c 11.2%)   │     │                                │
│                                          │     │                                │
└──────────────────────────────────────────┘     └────────────────────────────────┘
                     │
                     ▼
         SAME PATIENT CHART — all visible at:
         /openmrs/spa/patient/1a09907b-.../chart
```

### Key Links

| From | To | Via |
|---|---|---|
| Task | ServiceRequest | `Task.basedOn[0].reference` |
| Task | DiagnosticReport | `Task.output[0].valueReference` |
| Task | Observation (result) | `Task.output[1].valueReference` |
| DiagnosticReport | Observation | `DiagnosticReport.result[0].reference` |
| ServiceRequest | Original Encounter | `ServiceRequest.encounter` |
| Lab Encounter | Patient | `Encounter.subject` |

### Query Cheat Sheet

| What you want | FHIR Query |
|---|---|
| All tasks for patient | `GET /Task?patient=<uuid>` |
| Tasks for a specific referral | `GET /Task?based-on=ServiceRequest/<sr-uuid>` |
| Pending referrals | `GET /Task?patient=<uuid>&status=requested` |
| Completed referrals | `GET /Task?patient=<uuid>&status=completed` |
| All ServiceRequests for patient | `GET /ServiceRequest?patient=<uuid>` |
| Lab results for patient | `GET /DiagnosticReport?patient=<uuid>` |
| All encounters for patient | `GET /Encounter?patient=<uuid>` |
| Encounters at specific location | `GET /Encounter?patient=<uuid>&location=<loc-uuid>` |

---

## Complete Test Script

Save UUIDs from each step and run this all-in-one verification:

```bash
#!/bin/bash
# Replace these with actual UUIDs from the steps above
PATIENT_UUID="1a09907b-f084-483f-b8b3-2dc4347091d8"
ENCOUNTER_UUID="<from step 1a>"
SR_UUID="<from step 2a>"
TASK_UUID="<from step 2b>"
LAB_ENC_UUID="<from step 3b>"
HBAC1_OBS_UUID="<from step 3c>"
DIAG_UUID="<from step 3d>"

BASE="http://localhost:9096/openmrs/ws/fhir2/R4"
AUTH="-u admin:Admin123"

echo "=== 1. PATIENT ==="
curl -s $AUTH "$BASE/Patient/$PATIENT_UUID" | python3 -c "
import sys,json; p=json.load(sys.stdin)
nm=p['name'][0]
print(f'  {nm[\"family\"]} {\" \".join(nm[\"given\"])}')
for i in p.get('identifier',[]):
    print(f'  {i.get(\"type\",{}).get(\"text\",\"?\")}: {i[\"value\"]}')
"

echo ""
echo "=== 2. ORIGINAL ENCOUNTER (Outpatient Clinic) ==="
curl -s $AUTH "$BASE/Encounter/$ENCOUNTER_UUID" | python3 -c "
import sys,json; e=json.load(sys.stdin)
print(f'  status: {e[\"status\"]}')
print(f'  type: {e[\"type\"][0][\"coding\"][0][\"display\"]}')
print(f'  location: {e.get(\"location\",[{}])[0].get(\"location\",{}).get(\"display\",\"?\")}')
"

echo ""
echo "=== 3. SERVICE REQUEST (Referral) ==="
curl -s $AUTH "$BASE/ServiceRequest/$SR_UUID" | python3 -c "
import sys,json; sr=json.load(sys.stdin)
print(f'  status: {sr[\"status\"]}')
print(f'  code: {sr.get(\"code\",{}).get(\"text\",\"?\")}')
print(f'  intent: {sr[\"intent\"]}')
"

echo ""
echo "=== 4. TASK (Referral Tracking) ==="
curl -s $AUTH "$BASE/Task/$TASK_UUID" | python3 -c "
import sys,json; t=json.load(sys.stdin)
print(f'  status: {t[\"status\"]}')
print(f'  basedOn: {t.get(\"basedOn\",[{}])[0].get(\"reference\",\"?\")}')
for o in t.get('output',[]):
    print(f'  output: {o[\"type\"][\"text\"]} → {o[\"valueReference\"][\"display\"]}')
"

echo ""
echo "=== 5. LAB ENCOUNTER (Inpatient Ward) ==="
curl -s $AUTH "$BASE/Encounter/$LAB_ENC_UUID" | python3 -c "
import sys,json; e=json.load(sys.stdin)
print(f'  status: {e[\"status\"]}')
print(f'  type: {e[\"type\"][0][\"coding\"][0][\"display\"]}')
print(f'  location: {e.get(\"location\",[{}])[0].get(\"location\",{}).get(\"display\",\"?\")}')
"

echo ""
echo "=== 6. HbA1c OBSERVATION ==="
curl -s $AUTH "$BASE/Observation/$HBAC1_OBS_UUID" | python3 -c "
import sys,json; o=json.load(sys.stdin)
vq=o.get('valueQuantity',{})
print(f'  value: {vq.get(\"value\")} {vq.get(\"unit\")}')
print(f'  status: {o[\"status\"]}')
"

echo ""
echo "=== 7. DIAGNOSTIC REPORT ==="
curl -s $AUTH "$BASE/DiagnosticReport/$DIAG_UUID" | python3 -c "
import sys,json; dr=json.load(sys.stdin)
print(f'  status: {dr[\"status\"]}')
print(f'  conclusion: {dr.get(\"conclusion\",\"?\")}')
"

echo ""
echo "=== SUMMARY ==="
echo "Patient:         NSENGIMANA PATRICK ($PATIENT_UUID)"
echo "NCD Encounter:   $ENCOUNTER_UUID (Outpatient Clinic)"
echo "Referral:        $SR_UUID (HbA1c Test, status=active)"
echo "Task:            $TASK_UUID (status=completed)"
echo "Lab Encounter:   $LAB_ENC_UUID (Inpatient Ward)"
echo "HbA1c Result:    $HBAC1_OBS_UUID (11.2%)"
echo "Report:          $DIAG_UUID"
echo ""
echo "View in O3 UI: http://localhost:9096/openmrs/spa/patient/$PATIENT_UUID/chart"
```

---

## O3 Instance Reference — Real UUIDs Used

| Entity | UUID | Display |
|---|---|---|
| **Patient** | `1a09907b-f084-483f-b8b3-2dc4347091d8` | NSENGIMANA PATRICK |
| **Location: Outpatient Clinic** | `44c3efb0-2583-4c80-a79e-1f756a03c0a1` | NCD screening site |
| **Location: Inpatient Ward** | `ba685651-ed3b-4e63-9b35-78893060758a` | Lab/referral site |
| **Practitioner: Jane Nurse** | `1fee2f21-82f3-4aab-8d87-f1cf19034649` | NCD clinician |
| **Practitioner: June Technician** | `92416875-5fe2-4d3b-8531-e2c8c846a69d` | Lab technician |
| **Encounter Type: Visit Note** | `d7151f82-c1f3-4152-a605-2f9ea7414a79` | For NCD assessment |
| **Encounter Type: Lab Results** | `3596fafb-6f6f-4396-8c87-6e63a0f1bd71` | For lab encounter |
| **Concept: HbA1c** | `159644AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` | Glycosylated hemoglobin A |
| **Concept: Fasting Glucose** | `160912AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` | Fasting blood glucose |
| **Concept: Ophthalmology Referral** | `1373AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` | Referral concept |
| **Order Type: Test Order** | `52a447d3-a64a-11e3-9aeb-50e549534c5e` | For ServiceRequest |

---

## FHIR Resource Support in O3

| Resource | Create | Read | Update | Delete | Patch | Search |
|---|---|---|---|---|---|---|
| **Patient** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Encounter** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Observation** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Condition** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **DiagnosticReport** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Task** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **ServiceRequest** | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |

ServiceRequest creation is handled by the CCE Adaptor's `FhirToRestTransformer` which maps it to a TestOrder via the REST API.
