# CCE Receiver Adaptor for OpenMRS

A Spring Boot application that receives FHIR R4 resources from CCE Core and routes them to OpenMRS using a **hybrid FHIR + REST** approach driven by CapabilityStatement auto-discovery.

## Architecture

```
                     CCE Core / OpenHIM
                           │
                     POST /api/v1/fhir
                     (FHIR Bundle or
                      standalone resource)
                           │
                           ▼
              ┌─────────────────────────┐
              │   CCE Receiver Adaptor   │
              │                         │
              │  1. Parse FHIR payload  │
              │  2. Split Bundle →      │
              │     individual entries  │
              │  3. Check each entry    │
              │     against Capability  │
              │     Statement cache     │
              │  4. Route: FHIR or REST │
              └───────┬─────────┬───────┘
                      │         │
              FHIR-ok │         │ REST fallback
                      ▼         ▼
            ┌──────────┐  ┌──────────────┐
            │ OpenMRS  │  │   OpenMRS    │
            │ FHIR R4  │  │   REST v1   │
            │ Endpoint │  │   Endpoint  │
            └──────────┘  └──────────────┘
```

## Key Features

- **CapabilityStatement-driven routing** — Fetches `/metadata` from OpenMRS on startup and periodically; routes each resource through FHIR when the operation is supported, REST when it's not
- **Bundle decomposition** — Accepts both standalone FHIR resources and transaction Bundles; splits Bundles into individual entries and routes each independently
- **Dependency-aware ordering** — Creates Patients/Encounters before Orders/Observations to satisfy referential integrity
- **Cross-reference resolution** — Resolves `urn:uuid:` references within Bundles to server-assigned UUIDs
- **FHIR → REST transformation** — ServiceRequest → TestOrder, MedicationRequest → DrugOrder with full field mapping
- **Retry with exponential backoff** — Retries 5xx/timeout errors up to 3 times
- **Observability** — Micrometer metrics, Prometheus scrape endpoint, structured MDC logging

## Technology Stack

| Concern | Technology | Version |
|---------|-----------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build | Gradle (Groovy DSL) | 8.x |
| FHIR | HAPI FHIR | 7.4.0 |
| HTTP Client | Spring RestClient | (Spring 6.1+) |
| Retry | Spring Retry | |
| Metrics | Micrometer + Prometheus | |
| Testing | JUnit 5, WireMock | |

## Project Structure

```
org.openphc.cce.receiver/
├── CceReceiverAdaptorApplication.java     # Entry point
├── config/                                 # Spring configuration
│   ├── FhirConfig.java                     #   FhirContext singleton
│   ├── OpenMrsProperties.java              #   @ConfigurationProperties
│   ├── RestClientConfig.java               #   RestClient beans (FHIR + REST)
│   └── RetryConfig.java                    #   @EnableRetry
├── controller/                             # REST endpoints
│   ├── InboundResourceController.java      #   POST /api/v1/fhir
│   └── DiagnosticsController.java          #   GET /api/v1/capabilities, routing-table
├── fhir/                                   # FHIR utilities
│   ├── CapabilityStatementCache.java       #   Auto-refresh capability cache
│   └── FhirResourceParser.java             #   HAPI FHIR parse/encode
├── service/                                # Business logic
│   ├── InboundProcessingService.java       #   Orchestrates pipeline
│   ├── BundleSplitter.java                 #   Bundle → ResourceEntry[]
│   ├── ResourceRouter.java                 #   Route each entry (FHIR/REST)
│   ├── OpenMrsFhirClient.java              #   FHIR POST/PUT/DELETE
│   └── OpenMrsRestClient.java              #   REST POST/PUT/DELETE
├── transformer/                            # FHIR → REST conversion
│   └── FhirToRestTransformer.java          #   ServiceRequest → TestOrder, etc.
├── model/                                  # DTOs
│   ├── ResourceEntry.java                  #   Individual entry from Bundle
│   ├── RoutingResult.java                  #   Per-resource outcome
│   └── ProcessingResponse.java             #   Aggregate response
└── exception/                              # Error handling
    ├── FhirParsingException.java
    ├── ResourceTransformException.java
    ├── OpenMrsClientException.java
    └── GlobalExceptionHandler.java
```

## Quick Start

### Prerequisites
- Java 21+
- Docker & Docker Compose

### Run with Docker Compose (from parent openMRS directory)

```bash
cd /path/to/openMRS
docker compose up -d
```

This starts:
1. **MySQL** (port 3306) — OpenMRS database
2. **OpenMRS** (port 9095) — Reference application with FHIR R4
3. **Receiver Adaptor** (port 8083) — This service

OpenMRS takes ~3-5 min to start. The adaptor waits for OpenMRS health before starting.

### Run locally (with OpenMRS already running on port 9095)

```bash
cd openmrs-cce-receiver-adaptor
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Test the API

```bash
# Check routing table
curl http://localhost:8083/api/v1/routing-table | python3 -m json.tool

# Send a standalone Patient
curl -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Correlation-ID: test-123" \
  -d '{
    "resourceType": "Patient",
    "name": [{"family": "Test", "given": ["User"]}],
    "gender": "male"
  }'

# Send a Bundle with Encounter + ServiceRequest
curl -X POST http://localhost:8083/api/v1/fhir \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Bundle",
    "type": "transaction",
    "entry": [
      {
        "fullUrl": "urn:uuid:enc-1",
        "resource": {
          "resourceType": "Encounter",
          "status": "finished",
          "class": {"code": "AMB"},
          "subject": {"reference": "Patient/<uuid>"}
        },
        "request": {"method": "POST", "url": "Encounter"}
      },
      {
        "resource": {
          "resourceType": "ServiceRequest",
          "status": "active",
          "intent": "order",
          "code": {"coding": [{"code": "<concept-uuid>"}]},
          "subject": {"reference": "Patient/<uuid>"},
          "encounter": {"reference": "urn:uuid:enc-1"}
        },
        "request": {"method": "POST", "url": "ServiceRequest"}
      }
    ]
  }'
```

### Run tests

```bash
./gradlew test
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `openmrs.fhir.base-url` | `http://localhost:9095/openmrs/ws/fhir2/R4` | OpenMRS FHIR R4 base URL |
| `openmrs.rest.base-url` | `http://localhost:9095/openmrs/ws/rest/v1` | OpenMRS REST API v1 base URL |
| `openmrs.auth.username` | `admin` | OpenMRS Basic Auth username |
| `openmrs.auth.password` | `Admin123` | OpenMRS Basic Auth password |
| `openmrs.capability-refresh-ms` | `21600000` (6h) | CapabilityStatement refresh interval |

## Diagnostics

- `GET /api/v1/capabilities` — Current CapabilityStatement cache
- `GET /api/v1/routing-table` — FHIR vs REST routing table
- `GET /actuator/health` — Liveness/readiness probes
- `GET /actuator/prometheus` — Prometheus metrics scrape
