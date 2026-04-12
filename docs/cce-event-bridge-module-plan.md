# CCE Event Bridge Module — Development & Deployment Plan

## Problem Statement

OpenMRS O3's **FHIR2 Module (v3.1.0)** does NOT implement the FHIR R4 `Subscription` resource. This means REST-hook Subscriptions — what the FHIR CCE Emitter Adaptor uses to receive callbacks from FHIR servers — are not available on OpenMRS O3. You cannot `POST /ws/fhir2/R4/Subscription` to create a subscription.

The **Event Module (v4.0.0)** is an in-process Java event bus (based on ActiveMQ/JMS). It fires events within the OpenMRS JVM when data changes (patient created, encounter saved, etc.) but does **not** send HTTP callbacks to external services.

A bridge module is needed to connect these two systems.

---

## Solution

A **lightweight OpenMRS omod** (module JAR) that runs inside the O3 backend container. It bridges the Event Module v4.0.0 (in-process JMS bus) to the FHIR CCE Emitter Adaptor (external HTTP service) by:

1. Listening to JMS topics for data change events (Patient, Encounter, Observation, etc.)
2. Fetching the FHIR R4 representation from the co-located FHIR2 Module v3.1.0
3. POSTing the FHIR JSON to the emitter adaptor's `/callback/{resourceType}` endpoint

---

## Architecture

### System Context

```
Source Side (OpenMRS O3)                    Emitter                     HIE                        CCE Platform
────────────────────────                    ───────                     ───                        ────────────

┌─────────────────────────────────────────┐
│  OpenMRS O3 Container                   │
│                                         │
│  MariaDB ←→ OpenMRS Backend (2.8.x)    │
│                   │                     │
│                   ▼                     │
│         Event Module v4.0.0             │
│         (ActiveMQ JMS bus)              │
│                   │                     │
│          JMS Topic Events               │
│     (Patient.CREATED, Encounter.UPDATED)│
│                   │                     │
│                   ▼                     │
│  ┌─────────────────────────────────┐    │
│  │ 🆕 CCE Event Bridge Module     │    │
│  │ (omod JAR)                      │    │
│  └──────┬──────────────────────────┘    │
│         │                               │
│         │ GET /ws/fhir2/R4/{type}/{uuid}│
│         ▼                               │
│    FHIR2 Module v3.1.0                  │
│    (FHIR R4 REST API)                   │
│                                         │
└─────────┬───────────────────────────────┘
          │
          │ HTTP POST /callback/{resourceType}
          │ Content-Type: application/fhir+json
          │ Body: FHIR JSON
          ▼
┌──────────────────────────────┐
│ FHIR CCE Emitter Adaptor    │    HTTP POST     ┌─────────────┐   CloudEvents   ┌─────────────┐
│ (this service, port 9090)    │ ──────────────→  │ OpenHIM     │ ──────────────→ │ CCE         │
│ Receive → Forward            │   FHIR JSON      │ + Mediator  │                 │ Collector   │
└──────────────────────────────┘                  └─────────────┘                 │ → Kafka     │
                                                                                  └─────────────┘
```

### Event Flow — Data Change to CCE

```
1. Clinician saves Encounter in O3 UI (or any API: REST, FHIR2, direct service call)
2. OpenMRS Backend → Service Layer (EncounterService.saveEncounter())
3. Hibernate Interceptor → Event Module fires JMS event (Encounter.class, CREATED)
4. CCE Event Bridge Module receives JMS message (uuid, classname, action)
5. Bridge maps classname → FHIR resourceType (org.openmrs.Encounter → "Encounter")
6. Bridge calls FHIR2 Module: GET /ws/fhir2/R4/Encounter/{uuid}
7. FHIR2 Module returns FHIR R4 JSON
8. Bridge POSTs to Emitter Adaptor: POST /callback/Encounter (FHIR JSON body)
9. Emitter Adaptor returns 200 OK {"data":{"status":"ok"}}
10. Emitter Adaptor forwards to OpenHIM → CCE Collector → Kafka → Compliance
```

### Why Events Fire From All API Surfaces

The Event Module fires at the **Hibernate/service layer**, not the API layer:

```
Any API (REST module, FHIR2 module, O3 React UI, direct service call)
     │
     ▼
OpenMRS Service Layer (PatientService.savePatient(), etc.)
     │
     ▼
Hibernate Interceptor → Event Module fires JMS event
```

All paths end up at the same service layer, which triggers Hibernate, which triggers the Event Module. The bridge module captures changes from **all sources**.

---

## O3 Modules Involved

### Installed in O3 (from `distro/distro.properties`)

| Module | Version | Role |
|--------|---------|------|
| **Event Module** (`omod.event`) | 4.0.0 | In-process JMS event bus — fires events on data change |
| **FHIR2 Module** (`omod.fhir2`) | 3.1.0-SNAPSHOT | FHIR R4 REST API — translates OpenMRS data to FHIR |
| **REST Web Services** (`omod.webservices.rest`) | — | OpenMRS-native REST API (not directly used by bridge) |

### New Module

| Module | Version | Role |
|--------|---------|------|
| **CCE Event Bridge** (`omod.cceeventbridge`) | 1.0.0 | Listens to Event Module → fetches FHIR → POSTs to emitter adaptor |

---

## Event Module v4.0.0 API

### Key Interfaces

**`SubscribableEventListener`** — the primary interface for subscribing to events:

```java
public interface SubscribableEventListener extends EventListener {
    // Which OpenMRS domain objects to listen to
    List<Class<? extends OpenmrsObject>> subscribeToObjects();

    // Which actions to listen for
    List<String> subscribeToActions();
}
```

**`Event.Action`** enum defines the available actions:

| Action | Description |
|--------|-------------|
| `CREATED` | New record inserted |
| `UPDATED` | Existing record modified |
| `RETIRED` | Record retired (soft delete for metadata) |
| `UNRETIRED` | Record un-retired |
| `VOIDED` | Record voided (soft delete for clinical data) |
| `UNVOIDED` | Record un-voided |
| `PURGED` | Record permanently deleted |

**JMS Event Message** contains:

| Field | Description |
|-------|-------------|
| `uuid` | UUID of the changed OpenMRS object |
| `classname` | Fully qualified class name (e.g., `org.openmrs.Patient`) |
| `action` | The action that occurred (e.g., `CREATED`) |

### Auto-Registration

When a `SubscribableEventListener` is registered as a Spring bean in `moduleApplicationContext.xml`, the Event Module automatically subscribes it via JMS. The latest v4.0.0 includes `TransactionEventListener` (EVNT-49) which fires events only **after the transaction commits** — preventing forwarding of uncommitted data.

---

## FHIR Resource Type Mapping

### OpenMRS Domain Class → FHIR R4 Resource Type

| OpenMRS Class | FHIR Resource | FHIR2 Service | Event Fires? | Status |
|---------------|---------------|---------------|--------------|--------|
| `org.openmrs.Patient` | Patient | `FhirPatientService` | Yes | **Full support** |
| `org.openmrs.Encounter` | Encounter | `FhirEncounterService` | Yes | **Full support** |
| `org.openmrs.Obs` | Observation | `FhirObservationService` | Yes | **Full support** |
| `org.openmrs.Condition` | Condition | `FhirConditionService` | Yes | **Full support** |
| `org.openmrs.DrugOrder` | MedicationRequest | `FhirMedicationRequestService` | Yes | **Full support** |
| `org.openmrs.TestOrder` | ServiceRequest | `FhirServiceRequestService` | Yes | **Full support** |
| `org.openmrs.Obs` (grouped) | DiagnosticReport | `FhirDiagnosticReportService` | Yes (via Obs) | **Full support** |
| `org.openmrs.Location` | Location | `FhirLocationService` | Yes | **Full support** |
| `org.openmrs.Provider` | Practitioner | `FhirPractitionerService` | Yes | **Full support** |
| `org.openmrs.Cohort` | Group | `FhirGroupService` | Yes | **Full support** |
| `org.openmrs.Relationship` | RelatedPerson | `FhirRelatedPersonService` | Yes | **Full support** |

### Additional FHIR2 Resources (not in default 21 but available)

| OpenMRS Class | FHIR Resource | Notes |
|---------------|---------------|-------|
| `org.openmrs.Allergy` | AllergyIntolerance | Available if needed for CCE |
| `org.openmrs.Obs` (immunization) | Immunization | Specific concept-based |
| `org.openmrs.module.fhir2.model.FhirTask` | Task | OpenMRS Task support |
| `org.openmrs.PatientProgram` | EpisodeOfCare | Program enrollment mapping |

### Not Available in OpenMRS O3 (from the emitter adaptor's default 21)

| FHIR Resource | Reason |
|---------------|--------|
| Appointment | No FHIR2 endpoint (REST module only) |
| MedicationDispense | Not modeled in OpenMRS |
| MedicationStatement | Not modeled in OpenMRS |
| QuestionnaireResponse | Forms are internal to OpenMRS |
| CarePlan | Not modeled in OpenMRS |
| Coverage | Financial — not in OpenMRS |
| PaymentNotice | Financial — not in OpenMRS |
| Device | Not modeled in OpenMRS |
| Provenance | Metadata — not in OpenMRS |
| Organization | Not modeled natively in OpenMRS |

**11 of 21 resource types are fully supported** for OpenMRS O3.

---

## Module Structure

```
openmrs-module-cce-event-bridge/
├── pom.xml                              # Maven parent POM
├── api/
│   ├── pom.xml                          # API module POM
│   └── src/main/java/org/openmrs/module/cceeventbridge/
│       ├── CceEventBridgeConfig.java           # Global properties (emitter URL, resource types)
│       ├── FhirResourceFetcher.java            # Fetches FHIR JSON from FHIR2 module REST API
│       ├── EmitterForwarder.java               # HTTP POST to emitter adaptor /callback endpoint
│       └── CceEventBridgeListener.java         # SubscribableEventListener implementation
└── omod/
    ├── pom.xml                          # OMOD module POM
    └── src/main/resources/
        ├── config.xml                   # OpenMRS module descriptor
        └── moduleApplicationContext.xml # Spring bean wiring
```

---

## Core Implementation

### 1. The Listener — `CceEventBridgeListener.java`

Implements `SubscribableEventListener` to receive JMS events:

```java
package org.openmrs.module.cceeventbridge;

import org.openmrs.Cohort;
import org.openmrs.Condition;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.OpenmrsObject;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.Relationship;
import org.openmrs.Visit;
import org.openmrs.event.SubscribableEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import java.util.List;
import java.util.Map;

public class CceEventBridgeListener implements SubscribableEventListener {

    private static final Logger log = LoggerFactory.getLogger(CceEventBridgeListener.class);

    // OpenMRS domain classes → FHIR resource types
    private static final Map<String, String> CLASS_TO_FHIR = Map.ofEntries(
        Map.entry("org.openmrs.Patient",      "Patient"),
        Map.entry("org.openmrs.Encounter",    "Encounter"),
        Map.entry("org.openmrs.Obs",          "Observation"),
        Map.entry("org.openmrs.Condition",    "Condition"),
        Map.entry("org.openmrs.DrugOrder",    "MedicationRequest"),
        Map.entry("org.openmrs.TestOrder",    "ServiceRequest"),
        Map.entry("org.openmrs.Order",        "ServiceRequest"),
        Map.entry("org.openmrs.Visit",        "Encounter"),      // FHIR2 maps Visit → Encounter
        Map.entry("org.openmrs.Location",     "Location"),
        Map.entry("org.openmrs.Provider",     "Practitioner"),
        Map.entry("org.openmrs.Cohort",       "Group"),
        Map.entry("org.openmrs.Relationship", "RelatedPerson"),
        Map.entry("org.openmrs.Person",       "Patient")
    );

    private final FhirResourceFetcher fhirResourceFetcher;
    private final EmitterForwarder emitterForwarder;
    private final CceEventBridgeConfig config;

    public CceEventBridgeListener(FhirResourceFetcher fhirResourceFetcher,
                                   EmitterForwarder emitterForwarder,
                                   CceEventBridgeConfig config) {
        this.fhirResourceFetcher = fhirResourceFetcher;
        this.emitterForwarder = emitterForwarder;
        this.config = config;
    }

    @Override
    public List<Class<? extends OpenmrsObject>> subscribeToObjects() {
        return List.of(
            Patient.class, Encounter.class, Obs.class,
            Order.class, DrugOrder.class, Visit.class,
            Condition.class, Location.class, Provider.class,
            Cohort.class, Relationship.class
        );
    }

    @Override
    public List<String> subscribeToActions() {
        // Configurable via global property; defaults to CREATED + UPDATED
        return config.getActions();
    }

    @Override
    public void onMessage(Message message) {
        if (!config.isEnabled()) {
            return;
        }

        try {
            MapMessage mapMessage = (MapMessage) message;
            String uuid = mapMessage.getString("uuid");
            String classname = mapMessage.getString("classname");
            String action = mapMessage.getString("action");

            String fhirType = CLASS_TO_FHIR.get(classname);
            if (fhirType == null) {
                log.debug("No FHIR mapping for class: {}", classname);
                return;
            }

            log.info("Event received: {} {} (uuid={})", action, classname, uuid);

            // Fetch FHIR R4 JSON from local FHIR2 module
            String fhirJson = fhirResourceFetcher.fetch(fhirType, uuid);
            if (fhirJson == null) {
                log.warn("FHIR2 returned null for {}/{} — skipping", fhirType, uuid);
                return;
            }

            // POST to emitter adaptor callback endpoint
            emitterForwarder.forward(fhirType, fhirJson);

            log.info("Forwarded {} {} to emitter adaptor", fhirType, uuid);

        } catch (JMSException e) {
            log.error("Failed to process event message: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error processing event: {}", e.getMessage(), e);
        }
    }
}
```

### 2. FHIR Resource Fetcher — `FhirResourceFetcher.java`

Calls the local FHIR2 module REST API:

```java
package org.openmrs.module.cceeventbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Fetches FHIR R4 JSON from the co-located FHIR2 module REST API.
 *
 * GET http://localhost:8080/openmrs/ws/fhir2/R4/{resourceType}/{uuid}
 */
public class FhirResourceFetcher {

    private static final Logger log = LoggerFactory.getLogger(FhirResourceFetcher.class);

    private final RestTemplate restTemplate;
    private final CceEventBridgeConfig config;

    public FhirResourceFetcher(RestTemplate restTemplate, CceEventBridgeConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * Fetches FHIR R4 JSON for the given resource type and UUID.
     *
     * @param fhirType FHIR resource type (e.g., "Patient", "Encounter")
     * @param uuid     OpenMRS object UUID
     * @return FHIR JSON string, or null if fetch fails
     */
    public String fetch(String fhirType, String uuid) {
        // e.g. http://localhost:8080/openmrs/ws/fhir2/R4/Patient/{uuid}
        String url = config.getFhir2BaseUrl() + "/" + fhirType + "/" + uuid;

        try {
            String fhirJson = restTemplate.getForObject(url, String.class);
            log.debug("Fetched FHIR {} {} ({} bytes)", fhirType, uuid,
                    fhirJson != null ? fhirJson.length() : 0);
            return fhirJson;
        } catch (Exception e) {
            log.error("Failed to fetch FHIR {}/{}: {}", fhirType, uuid, e.getMessage());
            return null;
        }
    }
}
```

### 3. Emitter Forwarder — `EmitterForwarder.java`

HTTP POST to the emitter adaptor:

```java
package org.openmrs.module.cceeventbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

/**
 * Forwards FHIR JSON to the FHIR CCE Emitter Adaptor's callback endpoint.
 *
 * POST http://fhir-cce-emitter-adaptor:9090/callback/{resourceType}
 */
public class EmitterForwarder {

    private static final Logger log = LoggerFactory.getLogger(EmitterForwarder.class);

    private final RestTemplate restTemplate;
    private final CceEventBridgeConfig config;

    public EmitterForwarder(RestTemplate restTemplate, CceEventBridgeConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * POSTs FHIR JSON to the emitter adaptor callback endpoint.
     *
     * @param fhirType FHIR resource type (e.g., "Patient", "Encounter")
     * @param fhirJson raw FHIR JSON string
     */
    public void forward(String fhirType, String fhirJson) {
        // e.g. http://fhir-cce-emitter-adaptor:9090/callback/Patient
        String url = config.getEmitterBaseUrl() + config.getCallbackPath() + "/" + fhirType;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/fhir+json"));
        HttpEntity<String> request = new HttpEntity<>(fhirJson, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Forwarded {} to emitter adaptor: {}", fhirType, response.getStatusCode());
            } else {
                log.warn("Emitter adaptor returned {}: {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("Failed to forward {} to emitter adaptor at {}: {}",
                    fhirType, url, e.getMessage());
            // Fire-and-forget: log and drop. Do not block the OpenMRS event bus.
        }
    }
}
```

### 4. Configuration — `CceEventBridgeConfig.java`

Reads OpenMRS global properties:

```java
package org.openmrs.module.cceeventbridge;

import org.openmrs.api.AdministrationService;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration backed by OpenMRS Global Properties.
 */
public class CceEventBridgeConfig {

    private final AdministrationService adminService;

    // Global property keys
    private static final String GP_ENABLED = "cceeventbridge.enabled";
    private static final String GP_EMITTER_URL = "cceeventbridge.emitter.url";
    private static final String GP_CALLBACK_PATH = "cceeventbridge.callback.path";
    private static final String GP_FHIR2_BASE_URL = "cceeventbridge.fhir2.base.url";
    private static final String GP_ACTIONS = "cceeventbridge.actions";

    // Defaults
    private static final String DEFAULT_EMITTER_URL = "http://fhir-cce-emitter-adaptor:9090";
    private static final String DEFAULT_CALLBACK_PATH = "/callback";
    private static final String DEFAULT_FHIR2_URL = "http://localhost:8080/openmrs/ws/fhir2/R4";
    private static final String DEFAULT_ACTIONS = "CREATED,UPDATED";

    public CceEventBridgeConfig(AdministrationService adminService) {
        this.adminService = adminService;
    }

    public boolean isEnabled() {
        String val = adminService.getGlobalProperty(GP_ENABLED, "true");
        return Boolean.parseBoolean(val);
    }

    public String getEmitterBaseUrl() {
        return adminService.getGlobalProperty(GP_EMITTER_URL, DEFAULT_EMITTER_URL);
    }

    public String getCallbackPath() {
        return adminService.getGlobalProperty(GP_CALLBACK_PATH, DEFAULT_CALLBACK_PATH);
    }

    public String getFhir2BaseUrl() {
        return adminService.getGlobalProperty(GP_FHIR2_BASE_URL, DEFAULT_FHIR2_URL);
    }

    public List<String> getActions() {
        String actions = adminService.getGlobalProperty(GP_ACTIONS, DEFAULT_ACTIONS);
        return Arrays.asList(actions.split(","));
    }
}
```

### 5. Module Descriptor — `config.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<module configVersion="1.2">
    <id>cceeventbridge</id>
    <name>CCE Event Bridge</name>
    <version>1.0.0</version>
    <package>org.openmrs.module.cceeventbridge</package>
    <author>OpenPHC</author>
    <description>
        Bridges OpenMRS Event Module JMS events to the FHIR CCE Emitter Adaptor.
        Listens for data changes, fetches FHIR R4 representations via the FHIR2 module,
        and forwards them to the emitter adaptor's callback endpoint.
    </description>

    <require_version>2.5.0 - 2.*</require_version>

    <require_modules>
        <require_module version="4.0.0">org.openmrs.event</require_module>
        <require_module version="1.8.0">org.openmrs.module.fhir2</require_module>
    </require_modules>

    <globalProperty>
        <property>cceeventbridge.enabled</property>
        <defaultValue>true</defaultValue>
        <description>Enable/disable the CCE Event Bridge</description>
    </globalProperty>
    <globalProperty>
        <property>cceeventbridge.emitter.url</property>
        <defaultValue>http://fhir-cce-emitter-adaptor:9090</defaultValue>
        <description>Base URL of the FHIR CCE Emitter Adaptor</description>
    </globalProperty>
    <globalProperty>
        <property>cceeventbridge.callback.path</property>
        <defaultValue>/callback</defaultValue>
        <description>Callback path prefix on the emitter adaptor</description>
    </globalProperty>
    <globalProperty>
        <property>cceeventbridge.fhir2.base.url</property>
        <defaultValue>http://localhost:8080/openmrs/ws/fhir2/R4</defaultValue>
        <description>Base URL of the local FHIR2 module REST API</description>
    </globalProperty>
    <globalProperty>
        <property>cceeventbridge.actions</property>
        <defaultValue>CREATED,UPDATED</defaultValue>
        <description>Comma-separated event actions to listen for (CREATED,UPDATED,VOIDED,etc.)</description>
    </globalProperty>
</module>
```

### 6. Spring Bean Wiring — `moduleApplicationContext.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
           http://www.springframework.org/schema/beans/spring-beans-3.0.xsd">

    <bean id="cceEventBridgeConfig"
          class="org.openmrs.module.cceeventbridge.CceEventBridgeConfig">
        <constructor-arg ref="adminService"/>
    </bean>

    <bean id="cceRestTemplate"
          class="org.springframework.web.client.RestTemplate"/>

    <bean id="cceFhirResourceFetcher"
          class="org.openmrs.module.cceeventbridge.FhirResourceFetcher">
        <constructor-arg ref="cceRestTemplate"/>
        <constructor-arg ref="cceEventBridgeConfig"/>
    </bean>

    <bean id="cceEmitterForwarder"
          class="org.openmrs.module.cceeventbridge.EmitterForwarder">
        <constructor-arg ref="cceRestTemplate"/>
        <constructor-arg ref="cceEventBridgeConfig"/>
    </bean>

    <!-- The listener — auto-registered by Event Module via Event.setSubscription() -->
    <bean id="cceEventBridgeListener"
          class="org.openmrs.module.cceeventbridge.CceEventBridgeListener">
        <constructor-arg ref="cceFhirResourceFetcher"/>
        <constructor-arg ref="cceEmitterForwarder"/>
        <constructor-arg ref="cceEventBridgeConfig"/>
    </bean>

    <!-- Register the listener with the Event Module -->
    <bean id="cceEventSubscription" class="org.openmrs.event.Event">
        <property name="subscription" ref="cceEventBridgeListener"/>
    </bean>
</beans>
```

---

## Configuration (OpenMRS Global Properties)

| Global Property | Description | Default |
|----------------|-------------|---------|
| `cceeventbridge.enabled` | Enable/disable the bridge | `true` |
| `cceeventbridge.emitter.url` | Emitter adaptor base URL | `http://fhir-cce-emitter-adaptor:9090` |
| `cceeventbridge.callback.path` | Callback path prefix | `/callback` |
| `cceeventbridge.fhir2.base.url` | Local FHIR2 module REST API URL | `http://localhost:8080/openmrs/ws/fhir2/R4` |
| `cceeventbridge.actions` | Event actions to listen for (comma-separated) | `CREATED,UPDATED` |

---

## Deployment Steps

### Step 1: Build the omod JAR

```bash
cd openmrs-module-cce-event-bridge
mvn clean package
# → omod/target/cceeventbridge-1.0.0.omod
```

### Step 2: Add to O3 distro

Add to `distro/distro.properties`:

```properties
omod.cceeventbridge=${cceeventbridge.version}
omod.cceeventbridge.groupId=org.openphc.cce
```

Add version to `distro/pom.xml` `<properties>`:

```xml
<cceeventbridge.version>1.0.0</cceeventbridge.version>
```

Add dependency to `distro/pom.xml` `<dependencies>`:

```xml
<dependency>
    <groupId>org.openphc.cce</groupId>
    <artifactId>cceeventbridge-omod</artifactId>
    <version>${cceeventbridge.version}</version>
</dependency>
```

### Step 3: Rebuild the O3 backend image

```bash
cd /path/to/openMRS/O3
docker compose build backend
```

### Step 4: Configure via OpenMRS Admin UI or SQL

After the container starts, set global properties:

```sql
INSERT INTO global_property (property, property_value) VALUES
  ('cceeventbridge.emitter.url', 'http://fhir-cce-emitter-adaptor:9090'),
  ('cceeventbridge.fhir2.base.url', 'http://localhost:8080/openmrs/ws/fhir2/R4'),
  ('cceeventbridge.actions', 'CREATED,UPDATED');
```

Or via the OpenMRS Admin UI: **Administration → Advanced Settings → search "cceeventbridge"**.

### Step 5: Ensure Docker network connectivity

Both containers must be on the same Docker network. In the O3 `docker-compose.yml`:

```yaml
services:
  backend:
    networks:
      - default
      - emitter-network   # Same network as fhir-cce-emitter-adaptor

networks:
  emitter-network:
    external: true
```

### Step 6: Configure the FHIR CCE Emitter Adaptor

Disable startup subscriptions (OpenMRS doesn't support FHIR Subscriptions):

```yaml
EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED: "false"
```

The emitter adaptor's `/callback/{resourceType}` endpoint requires no changes — it already accepts FHIR JSON POSTs from any source.

---

## Key Considerations

### No Changes to the FHIR CCE Emitter Adaptor

The emitter adaptor already accepts `POST /callback/{resourceType}` with FHIR JSON body. The bridge module replaces FHIR server REST-hook subscriptions — it's just a different event source pushing to the same endpoint.

### Transaction Safety

Event Module v4.0.0 added `TransactionEventListener` (EVNT-49, March 2026) which fires events only **after the transaction commits**. This prevents forwarding uncommitted/rolled-back data.

### Failure Handling

The bridge module uses **fire-and-forget** semantics:
- If the emitter adaptor is unreachable → log ERROR, drop the event
- If FHIR2 returns null (deleted resource?) → log WARN, skip
- Do NOT block the OpenMRS event bus or retry indefinitely
- The emitter adaptor has its own retry logic for forwarding to OpenHIM

### No New Dependencies on the Emitter Side

The bridge module is completely decoupled. It POSTs to the same `/callback` endpoint that any FHIR server would use via REST-hook. The emitter adaptor doesn't know or care whether the callback came from a FHIR Subscription or the bridge module.

### Testing

- Unit test the listener with mock JMS messages, mock fetcher, mock forwarder
- Integration test with WireMock stubs for FHIR2 and emitter adaptor endpoints
- End-to-end test: save a Patient in O3 UI → verify FHIR JSON arrives at the emitter adaptor

---

## Alternatives Considered

### Option 1: FHIR Polling

Poll the FHIR2 module's REST API for changes using `_lastUpdated`:

```
GET /openmrs/ws/fhir2/R4/Patient?_lastUpdated=gt2024-01-15T10:00:00Z
```

**Pros:** No modules to install, uses existing FHIR2 module.
**Cons:** Not real-time (polling interval delay), more network traffic, need to track last-polled timestamp.

### Option 2: Database CDC (Debezium)

Capture changes from MariaDB directly using Debezium.

**Pros:** No OpenMRS changes needed, real-time.
**Cons:** Complex setup, need DB-to-FHIR mapping, infrastructure overhead.

### Why Option 3 (Bridge Module) Was Chosen

- Real-time event delivery (JMS, not polling)
- Leverages already-installed Event Module v4.0.0
- FHIR JSON from the official FHIR2 module (no custom mapping)
- Lightweight (~1 omod JAR, ~4 classes)
- Decoupled from the emitter adaptor (same `/callback` API)
- Transaction-safe (post-commit events)
