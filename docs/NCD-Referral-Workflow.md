# NCD Referral Workflow — Diabetes Management in Rwanda

> **Real-world scenario:** End-to-end referral flow using OpenMRS FHIR resources

---

## The Scenario

| | Details |
|---|---|
| **Patient** | Jean-Pierre HABIMANA, 52-year-old male farmer from Musanze district |
| **NCD** | Type 2 Diabetes with suspected diabetic retinopathy |
| **Health Center (Origin)** | Ruhengeri Health Center |
| **Referral Hospital** | CHUB (Centre Hospitalier Universitaire de Butare) |

---

## Step 1: NCD Screening at Ruhengeri Health Center

Jean-Pierre visits Ruhengeri Health Center for his routine NCD follow-up. The nurse takes vitals and the doctor reviews his condition.

### What happens in OpenMRS:

```
Visit (auto-created)
  └── Encounter: "NCD Follow-Up"
        ├── Observation: Fasting Blood Glucose = 310 mg/dL     (LOINC: 1558-6)
        ├── Observation: HbA1c = 11.2%                          (LOINC: 4548-4)
        ├── Observation: Blood Pressure = 155/95 mmHg           (LOINC: 85354-9)
        ├── Observation: BMI = 31.4                              (LOINC: 39156-5)
        ├── Observation: Visual acuity (Left) = 20/60            (LOINC: 79880-1)
        ├── Observation: Visual acuity (Right) = 20/50           (LOINC: 79881-9)
        ├── Condition: Type 2 Diabetes Mellitus (active)         (ICD-10: E11)
        ├── Condition: Essential Hypertension (active)           (ICD-10: I10)
        ├── MedicationRequest: Metformin 1000mg BID              (continuing)
        └── MedicationRequest: Amlodipine 10mg daily             (continuing)
```

The doctor notices Jean-Pierre's deteriorating vision (20/60 left, 20/50 right) and poorly controlled diabetes (HbA1c 11.2%). He suspects **diabetic retinopathy** and decides to refer him to CHUB for an ophthalmology exam and retinal screening.

---

## Step 2: Doctor Creates Referral Order

The doctor fills out the referral form in OpenMRS.

### ServiceRequest (Referral Order)

```json
{
  "resourceType": "ServiceRequest",
  "status": "active",
  "intent": "order",
  "priority": "urgent",
  "code": {
    "coding": [
      {
        "system": "http://snomed.info/sct",
        "code": "274798009",
        "display": "Ophthalmology referral"
      }
    ]
  },
  "subject": {
    "reference": "Patient/<jean-pierre-uuid>"
  },
  "encounter": {
    "reference": "Encounter/<ncd-followup-uuid>"
  },
  "requester": {
    "reference": "Practitioner/<dr-mugabo-uuid>",
    "display": "Dr. Mugabo (NCD Clinic)"
  },
  "performer": [
    {
      "reference": "Organization/<chub-uuid>",
      "display": "CHUB - Ophthalmology Dept"
    }
  ],
  "reasonCode": [
    {
      "coding": [
        {
          "system": "http://snomed.info/sct",
          "code": "4855003",
          "display": "Diabetic retinopathy"
        }
      ]
    }
  ],
  "reasonReference": [
    { "reference": "Observation/<visual-acuity-left-uuid>" },
    { "reference": "Observation/<hba1c-uuid>" }
  ],
  "note": [
    {
      "text": "Bilateral decreased visual acuity with poorly controlled DM. Rule out diabetic retinopathy. Urgent retinal exam needed."
    }
  ]
}
```

### Task (Tracks Fulfillment)

```json
{
  "resourceType": "Task",
  "status": "requested",
  "intent": "order",
  "priority": "urgent",
  "code": { "text": "Referral Follow-Up" },
  "description": "Ophthalmology evaluation for suspected diabetic retinopathy",
  "for": {
    "reference": "Patient/<jean-pierre-uuid>",
    "display": "HABIMANA Jean-Pierre"
  },
  "basedOn": [
    { "reference": "ServiceRequest/<referral-uuid>" }
  ],
  "encounter": {
    "reference": "Encounter/<ncd-followup-uuid>"
  },
  "requester": {
    "reference": "Practitioner/<dr-mugabo-uuid>"
  },
  "owner": {
    "reference": "Organization/<chub-uuid>"
  },
  "location": {
    "reference": "Location/<chub-ophthalmology-uuid>"
  }
}
```

### State at Ruhengeri after referral creation:

- Active referral to CHUB → ServiceRequest status = `active`
- Task status = `requested` (pending acceptance by CHUB)

---

## Step 3: RHIE Transmits Referral to CHUB

If both facilities are connected via RHIE:

```
Ruhengeri HC                     RHIE                          CHUB
    │                              │                              │
    ├── ServiceRequest ──────────►│──── ServiceRequest ─────────►│
    ├── Task (requested) ────────►│──── Task (requested) ───────►│
    ├── Patient demographics ────►│──── Patient demographics ───►│
    ├── Key Observations ────────►│──── Key Observations ───────►│
    │                              │                              │
```

The **CCE Receiver Adaptor** at CHUB receives this FHIR Bundle and persists everything in CHUB's OpenMRS.

---

## Step 4: Jean-Pierre Arrives at CHUB Ophthalmology

Two weeks later, Jean-Pierre presents to CHUB with his referral letter. The receptionist finds him in the system (already synced via RHIE).

### CHUB updates the Task:

```json
{
  "resourceType": "Task",
  "id": "<task-uuid>",
  "status": "accepted",
  "owner": {
    "reference": "Practitioner/<dr-uwamahoro-uuid>",
    "display": "Dr. Uwamahoro (Ophthalmologist)"
  }
}
```

> Task status changes from `requested` → `accepted`

### CHUB creates a new Encounter:

```
Visit (at CHUB)
  └── Encounter: "Ophthalmology Consultation"
        ├── basedOn: ServiceRequest/<referral-uuid>      ← links to original referral
        │
        ├── Observation: Intraocular Pressure (L) = 22 mmHg    (LOINC: 56844-4)
        ├── Observation: Intraocular Pressure (R) = 20 mmHg    (LOINC: 56845-1)
        ├── Observation: Fundoscopy Findings = "Microaneurysms, dot hemorrhages,
        │                hard exudates bilateral"                (SNOMED: 399625000)
        ├── Observation: Retinopathy Grade = "Moderate NPDR"     (SNOMED: 390834004)
        │
        ├── DiagnosticReport: "Retinal Examination Report"
        │     status: final
        │     conclusion: "Moderate non-proliferative diabetic retinopathy (NPDR)
        │                  bilateral. No macular edema. Recommend laser
        │                  photocoagulation if progression."
        │     result: [Observation/iop-left, Observation/iop-right,
        │              Observation/fundoscopy, Observation/retinopathy-grade]
        │
        ├── Condition: Diabetic retinopathy, moderate (CONFIRMED)  (ICD-10: E11.3)
        │
        ├── CarePlan: "Diabetic Retinopathy Management"
        │     - Follow-up retinal exam in 3 months
        │     - Tighten glycemic control (target HbA1c < 7%)
        │     - Consider pan-retinal photocoagulation if NPDR progresses
        │
        └── ServiceRequest: "Follow-up retinal exam in 3 months"
              status: active
              occurrence: 2026-07-03
```

---

## Step 5: CHUB Completes Task and Sends Results Back

The ophthalmologist completes the evaluation and updates the Task:

### Task (completed with output references):

```json
{
  "resourceType": "Task",
  "id": "<task-uuid>",
  "status": "completed",
  "executionPeriod": {
    "start": "2026-04-17T09:00:00+02:00",
    "end": "2026-04-17T10:30:00+02:00"
  },
  "output": [
    {
      "type": { "text": "DiagnosticReport" },
      "valueReference": {
        "reference": "DiagnosticReport/<retinal-exam-uuid>",
        "display": "Retinal Examination Report"
      }
    },
    {
      "type": { "text": "Condition" },
      "valueReference": {
        "reference": "Condition/<diabetic-retinopathy-uuid>",
        "display": "Moderate NPDR bilateral"
      }
    }
  ]
}
```

### ServiceRequest updated:

```json
{
  "resourceType": "ServiceRequest",
  "id": "<referral-uuid>",
  "status": "completed"
}
```

> ServiceRequest status changes from `active` → `completed`

### RHIE transmits results back to Ruhengeri:

```
CHUB                              RHIE                     Ruhengeri HC
  │                                │                            │
  ├── Task (completed) ──────────►│──── Task (completed) ─────►│
  ├── ServiceRequest (completed)─►│──── ServiceRequest ───────►│
  ├── DiagnosticReport ──────────►│──── DiagnosticReport ─────►│
  ├── Condition (retinopathy) ───►│──── Condition ────────────►│
  ├── Observations (IOP, etc.) ──►│──── Observations ─────────►│
  │                                │                            │
```

---

## Step 6: Jean-Pierre Returns to Ruhengeri for NCD Follow-Up

One month later, Jean-Pierre returns to Ruhengeri HC for his regular NCD visit. Dr. Mugabo opens his chart.

### What Dr. Mugabo sees in OpenMRS:

| Item | Status | Details |
|---|---|---|
| **Referral to CHUB** | **Completed** ✅ | ServiceRequest status = `completed` |
| **Task** | **Completed** ✅ | Has output references to results |
| **Diagnosis** | **Confirmed** | Moderate NPDR bilateral (E11.3) |
| **Report** | **Available** | "Retinal Examination Report" — DiagnosticReport |
| **Recommendations** | **Noted** | Tighten glycemic control, follow-up 3 months |

### How Dr. Mugabo queries this (behind the scenes):

```
1. GET /Task?patient=<jean-pierre>&based-on=ServiceRequest/<referral-uuid>
   → Task status=completed, output=[DiagnosticReport/<uuid>, Condition/<uuid>]

2. GET /DiagnosticReport/<retinal-exam-uuid>
   → conclusion: "Moderate NPDR bilateral..."

3. GET /Condition?patient=<jean-pierre>&category=encounter-diagnosis
   → Includes new: "Diabetic retinopathy, moderate" from CHUB
```

### Dr. Mugabo creates a new NCD Encounter based on findings:

```
Encounter: "NCD Follow-Up" (Post-Referral)
  ├── Observation: Fasting Blood Glucose = 245 mg/dL      (improving)
  ├── Observation: HbA1c = 9.8%                           (still high)
  ├── Observation: BP = 140/88 mmHg                        (improving)
  │
  ├── MedicationRequest: Metformin 1000mg BID              (continue)
  ├── MedicationRequest: Amlodipine 10mg daily             (continue)
  ├── MedicationRequest: Glipizide 5mg daily               (NEW — added per CHUB advice)
  │
  ├── CarePlan: Updated NCD Plan
  │     - Target HbA1c < 7% (per ophthalmologist recommendation)
  │     - Retinal follow-up at CHUB in July 2026
  │     - Monthly glucose monitoring
  │
  └── ServiceRequest: "Repeat HbA1c in 3 months"
        status: active
        occurrence: 2026-07-03
```

---

## Complete Timeline

```
Day 0  │ Ruhengeri HC  │ NCD Follow-Up: High glucose, poor vision detected
       │               │ → ServiceRequest: Refer to CHUB Ophthalmology
       │               │ → Task: status = "requested"
       │               │
Day 1  │ RHIE          │ Transmits referral bundle to CHUB
       │               │
Day 14 │ CHUB          │ Jean-Pierre arrives, Task → "accepted"
       │               │ Encounter: Retinal exam, IOP, fundoscopy
       │               │ DiagnosticReport: "Moderate NPDR bilateral"
       │               │ Task → "completed" with output references
       │               │ ServiceRequest → "completed"
       │               │
Day 15 │ RHIE          │ Transmits results back to Ruhengeri
       │               │
Day 45 │ Ruhengeri HC  │ NCD Follow-Up: Dr. reviews CHUB results
       │               │ Adjusts medications (adds Glipizide)
       │               │ Updates CarePlan with tighter HbA1c targets
       │               │ Schedules CHUB follow-up in 3 months
```

---

## The FHIR Resource Chain

```
                    Ruhengeri HC                                    CHUB
                    ──────────                                    ────
                         │                                          │
NCD Encounter ──────────►│                                          │
  ├── Observations       │                                          │
  ├── Conditions         │                                          │
  └── ServiceRequest ────┼──── (via RHIE) ──────────────────────────┤
        │                │                                          │
        └── Task ────────┼──── (via RHIE) ──►  Task (accepted)      │
              │          │                       │                   │
              │          │                    Ophthal Encounter ────►│
              │          │                       ├── Observations    │
              │          │                       ├── DiagnosticReport│
              │          │                       └── Condition       │
              │          │                       │                   │
              │    ◄─────┼──── (via RHIE) ◄──── Task (completed)    │
              │          │                       output: [Report]    │
              ▼          │                                          │
   Task = completed      │                                          │
   ServiceRequest =      │                                          │
     completed           │                                          │
   DiagnosticReport      │                                          │
     visible locally     │                                          │
                         │                                          │
Post-Referral NCD ──────►│                                          │
  Encounter              │                                          │
  ├── Adjusted meds      │                                          │
  └── Updated CarePlan   │                                          │
```

---

## Key FHIR Resources in the Referral Flow

| Step | Resource | Who Creates | Status Flow |
|---|---|---|---|
| Doctor orders referral | **ServiceRequest** | Facility A (Ruhengeri) | `draft` → `active` |
| Track fulfillment | **Task** | Facility A (or RHIE) | `requested` → `accepted` → `in-progress` → `completed` |
| Patient visits referral site | **Encounter** | Facility B (CHUB) | `planned` → `in-progress` → `finished` |
| Tests/exams done | **Observation** / **DiagnosticReport** | Facility B (CHUB) | `preliminary` → `final` |
| Results sent back | **Task** (updated) | Facility B (CHUB) | `output` field populated |
| Original doctor sees results | **Task** query | Facility A reads (Ruhengeri) | status = `completed` + output ref |

---

## How Status Reflects Back at Ruhengeri

The clinician at Ruhengeri queries the referral status:

```
GET /Task?based-on=ServiceRequest/<referral-uuid>&status=completed
```

This returns the Task with `output` containing references to the DiagnosticReport and Observations from CHUB. The ServiceRequest itself is also updated:

```json
{
  "resourceType": "ServiceRequest",
  "id": "<referral-uuid>",
  "status": "completed",
  "subject": { "reference": "Patient/<jean-pierre-uuid>" },
  "encounter": { "reference": "Encounter/<ncd-followup-uuid>" },
  "supportingInfo": [
    { "reference": "DiagnosticReport/<retinal-exam-report-uuid>" }
  ]
}
```

---

## What Our CCE Receiver Adaptor Handles

| Resource | Adaptor Support | Notes |
|---|---|---|
| ServiceRequest | Read-only FHIR → REST fallback (TestOrder) | Can create referral orders via REST API |
| Task | Full CRUD via FHIR | Status updates work end-to-end |
| DiagnosticReport | Full CRUD via FHIR | Results from referral site |
| Encounter | Full CRUD via FHIR | Both origin and referral encounters |
| Observation | Create (2.x) / Full CRUD (O3) | Test results, vitals |

The adaptor can receive all these resources from RHIE and persist them in OpenMRS. For the **return flow** (results back to Ruhengeri), the RHIE would send updated Task + DiagnosticReport payloads to our adaptor at Ruhengeri.

---

## Key Takeaway

- **ServiceRequest** is the referral order
- **Task** is the tracking mechanism across facilities
- **DiagnosticReport** carries the referral results
- The **RHIE** (with our CCE Receiver Adaptor) handles the bidirectional data flow so both facilities see a complete picture of the patient's journey
