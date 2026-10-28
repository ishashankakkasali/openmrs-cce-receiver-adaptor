# OpenMRS Notification Integration

How the `openmrs-receiver` adaptor should push user-facing notifications into
OpenMRS so the **CCE Notifications** ESM (bell + top-right toast) picks them
up.

---

## 1. Where this fits in the pipeline

```
SPICE / CCE
    │  FHIR Bundle (incl. ServiceRequest = referral)
    ▼
openmrs-receiver adaptor
    │  [existing] POST /openmrs/ws/rest/v1/order  ← creates the referral order
    │  [new]      POST /openmrs/ws/rest/v1/alert  ← fires the notification
    ▼
OpenMRS REST + DB
    │
    ▼  (polled every 30s by the ESM)
OpenMRS 3 SPA → Bell badge + top-right toast for receiving clinicians
```

The adaptor's only new responsibility is **fire-and-forget** of a single REST
call after a successful referral order create. The ESM owns polling,
rendering, mark-read, navigation.

---

## 2. Endpoint

| Property | Value |
|---|---|
| Method | `POST` |
| URL | `{openmrs.rest.baseUrl}/alert` &nbsp;→&nbsp; e.g. `http://o3-backend:8080/openmrs/ws/rest/v1/alert` |
| Content-Type | `application/json` |
| Auth | HTTP Basic with a service user that has the `Manage Alerts` privilege |
| Idempotency | None server-side — caller MUST suppress retries that would create duplicates |
| Success response | `201 Created`, body `{ "uuid": "...", "alertId": N, ... }` |

The adaptor already has a working `RestClient` bean (`openmrsRestClient`) that
handles base URL + Basic auth. Reuse it — see [Section 7](#7-suggested-spring-implementation).

### Recommended service account

Don't reuse `admin`. Create once, manually, in OpenMRS:

```
User:        cce-notifier
Privileges:  Manage Alerts, View Users   (the second is needed only if the
                                          adaptor resolves recipients via
                                          /user search)
Roles:       (create a minimal custom role bundling the two privileges)
```

Then put credentials in the adaptor's existing `openmrs.auth.username` /
`password` config — or in a dedicated `notifier` sub-block if you want to keep
the data-write user separate from the alert-write user.

---

## 3. Request body

```json
{
  "text": "<NotificationPayload JSON, stringified>",
  "satisfiedByAny": false,
  "alertRead": false,
  "dateToExpire": "2026-05-14T23:59:59.000+0000",
  "recipients": [
    { "recipient": "<openmrs user uuid 1>" },
    { "recipient": "<openmrs user uuid 2>" }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `text` | string, **≤ 512 chars** total | The whole `NotificationPayload` JSON, stringified. 512 is a hard DB column limit on `notification_alert.text`. |
| `satisfiedByAny` | bool | Always `false` — each recipient's read-state is tracked independently. |
| `alertRead` | bool | Always `false` on create. |
| `dateToExpire` | ISO-8601 with offset | Recommended `now + 48h`. Past-expiry alerts are filtered by the ESM. |
| `recipients` | array | Must contain at least one entry. `recipient` = OpenMRS **user UUID** (not person UUID). |

### Notes
- `recipient` is the user UUID returned by `GET /ws/rest/v1/user`. Person UUIDs
  are silently rejected.
- The OpenMRS REST `AlertResource` does **not** allow updating `alertRead`
  after creation — the ESM persists read-state client-side. Don't try to
  PATCH this from the adaptor.

---

## 4. `text` payload schema

```ts
interface NotificationPayload {
  v: 1;                                   // schema version sentinel
  type?: string;                          // free-form event tag (analytics only)
  severity?: 'INFO' | 'WARNING' | 'CRITICAL';
  title: string;                          // REQUIRED — shown bold
  body?: string;                          // 1-line subtitle
  link?: string;                          // SPA URL the user navigates to on click
}
```

### Field semantics (what the ESM actually does)

| Field | UI effect |
|---|---|
| `title` | Bell row title + toast title. **Required.** If missing, the entire payload falls back to legacy rendering (raw text as title). |
| `body` | Sub-line in bell row + subtitle in toast. Optional. |
| `severity` | Determines whether a toast pops *(via configurable `snackbarSeverities`; default = every severity)*, the toast's left-bar colour (red/yellow/blue), and the Carbon `<Tag>` colour in the bell. Missing → treated as `INFO`. |
| `link` | If present, clicking the bell row or the toast's **Open** button navigates here via `window.location.assign(link)`. Use SPA-relative paths like `/openmrs/spa/...`. If missing, the row is still clickable (marks read) but does not navigate. |
| `type` | Stored but currently unused by the UI. Reserved for future filtering & analytics. Pick from the recommended list below or send your own. |
| `v` | Schema version; always `1`. |

### Recommended `type` values for referrals

| `type` | When the adaptor should fire |
|---|---|
| `REFERRAL_ARRIVED` | New referral order created from inbound `ServiceRequest`. |
| `REFERRAL_DELAYED` | Future use — referral not actioned within SLA. |
| `REFERRAL_DECLINED` | Future use — referral rejected. |
| `REFERRAL_ACCEPTED` | Future use — closing the loop. |

Strings are free-form; the ESM ignores unknown values gracefully.

---

## 5. Size budget (≤ 512 chars)

Plan for envelope plus payload. Approximate share:

```
title  ≤ 60  chars
body   ≤ 120 chars
link   ≤ 150 chars
type + severity + v + JSON braces ≈ 40 chars
                                   ────
                                   ~370 chars  (comfortable headroom)
```

If a generated body would exceed the budget, **truncate the `body`** (not the
title or link). Suggested adaptor helper:

```java
private String clampPayloadToColumn(String json) {
    if (json.length() <= 512) return json;
    throw new IllegalStateException("Notification payload exceeds 512 chars: " + json.length());
}
```

…then have callers shorten `body` until the assertion passes.

---

## 6. Link targets

The OpenMRS 3 SPA URL for the patient chart **Referrals** tab is:

```
/openmrs/spa/patient/{patientUuid}/chart/Referrals
```

So for a freshly-created referral order, the adaptor should send:

```json
"link": "/openmrs/spa/patient/<patientUuid>/chart/Referrals"
```

`patientUuid` is the OpenMRS Patient UUID the adaptor already resolves in the
referral flow (see `PatientProvisioner` / `ReferenceResolver`).

> Use the SPA path (`/openmrs/spa/...`), not the REST path (`/openmrs/ws/...`).
> The ESM calls `window.location.assign(link)` and stays inside the SPA's
> client-side router for SPA links.

---

## 7. Suggested Spring implementation

Add a thin client. Reuses the existing `openmrsRestClient` bean and its auth.

### `src/main/java/.../service/OpenMrsNotificationClient.java`

```java
package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class OpenMrsNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsNotificationClient.class);
    private static final int TEXT_COLUMN_LIMIT = 512;

    private final RestClient restClient;
    private final ObjectMapper om = new ObjectMapper();

    public OpenMrsNotificationClient(@Qualifier("openmrsRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fire-and-forget. Logs and swallows failures — a missed notification
     * must NEVER fail the referral order creation flow.
     */
    public void notify(NotificationPayload payload, List<String> recipientUserUuids) {
        if (recipientUserUuids == null || recipientUserUuids.isEmpty()) {
            log.debug("Skipping notify — no recipients resolved (title='{}')", payload.title());
            return;
        }
        try {
            String text = buildText(payload);
            ObjectNode body = om.createObjectNode();
            body.put("text", text);
            body.put("satisfiedByAny", false);
            body.put("alertRead", false);
            body.put("dateToExpire",
                Instant.now().plus(48, ChronoUnit.HOURS).toString());
            var recipients = body.putArray("recipients");
            recipientUserUuids.forEach(uuid ->
                recipients.addObject().put("recipient", uuid));

            restClient.post()
                .uri("/alert")
                .body(body.toString())
                .retrieve()
                .body(String.class);

            log.info("Pushed alert title='{}' to {} recipient(s)",
                payload.title(), recipientUserUuids.size());
        } catch (Exception e) {
            log.warn("Failed to push alert title='{}': {}", payload.title(), e.toString());
        }
    }

    private String buildText(NotificationPayload p) {
        ObjectNode n = om.createObjectNode();
        n.put("v", 1);
        if (p.type() != null)     n.put("type", p.type());
        if (p.severity() != null) n.put("severity", p.severity());
        n.put("title", p.title());
        if (p.body() != null)     n.put("body", p.body());
        if (p.link() != null)     n.put("link", p.link());
        String s = n.toString();
        if (s.length() > TEXT_COLUMN_LIMIT) {
            // Truncate body only — preserve routing & semantics.
            int over = s.length() - TEXT_COLUMN_LIMIT;
            String trimmedBody = p.body() == null ? null
                : p.body().substring(0, Math.max(0, p.body().length() - over - 1));
            n.put("body", trimmedBody == null ? "" : trimmedBody + "…");
            s = n.toString();
        }
        return s;
    }

    public record NotificationPayload(
        String type,
        String severity,   // INFO | WARNING | CRITICAL
        String title,
        String body,
        String link
    ) {}
}
```

### Hook into the referral flow

In `ResourceRouter` (or wherever a `ServiceRequest` becomes a successful
order), after the `openMrsRestClient.create("/order", …)` call returns:

```java
RoutingResult result = openMrsRestClient.create("/order", payload, resourceType);

if (result.isSuccess()
        && referralProperties.isEnabled()
        && fhirToRestTransformer.isReferral(resourceNode)) {

    String patientUuid    = extractPatientUuid(resourceNode);   // already resolved upstream
    String orderUuid      = result.uuid();
    List<String> targets  = recipientResolver.resolveForReferral(resourceNode);

    notificationClient.notify(
        new OpenMrsNotificationClient.NotificationPayload(
            "REFERRAL_ARRIVED",
            "INFO",                                        // or WARNING for urgent referrals
            "New referral received",
            "Patient " + shortName + " routed for " + serviceTypeLabel,
            "/openmrs/spa/patient/" + patientUuid + "/chart/Referrals"
        ),
        targets
    );
}
```

---

## 8. Recipient resolution

The adaptor must turn a referral into a set of **OpenMRS user UUIDs**. Choose
the policy that matches your operational model:

| Policy | How |
|---|---|
| **Role-based** | `GET /ws/rest/v1/user?role=Clinician&v=custom:(uuid)` and collect uuids. Cache for 5 min. |
| **Location-based** | `GET /ws/rest/v1/user?q=&v=custom:(uuid,userProperties)` then filter by `userProperties.defaultLocation == referralTargetLocation`. |
| **Hybrid (recommended)** | role ∩ defaultLocation. Falls back to role-only if zero matches. |
| **Single dedicated inbox** | Hardcode one user (e.g. `referral-inbox@clinic`) and have a dispatcher app forward. Simplest, least flexible. |

Cache the resolved set per `(role, location)` key for ~5 minutes — the
`/user` endpoint is not free.

---

## 9. Failure & retry policy

| Failure mode | Adaptor behaviour |
|---|---|
| Network blip / 5xx | Retry up to 3× with backoff (reuse existing `@Retryable` pattern). |
| 4xx (e.g. validation, auth) | Log WARN with response body, do not retry. |
| 0 recipients resolved | Log INFO, skip the POST. |
| Payload > 512 chars after truncation | Throw — indicates a bug in title/link generation. |
| Any exception | **Never** propagate up — must not fail the referral order create. |

Idempotency: the adaptor should remember `(serviceRequestId → alertUuid)` for
~24h so retries of the same FHIR bundle don't create duplicate alerts. A
simple in-memory `Caffeine` cache or the existing `SourceIdMappingStore`
pattern works.

---

## 10. End-to-end smoke test

Once integrated, smoke-test with the existing local OpenMRS stack:

```bash
# 1. Find the admin user UUID
ADMIN_UUID=$(curl -s -u admin:Admin123 \
  http://localhost:9096/openmrs/ws/rest/v1/session \
  | jq -r '.user.uuid')

# 2. Fire a sample alert directly (mimics the adaptor)
curl -s -u cce-notifier:<pwd> -H 'Content-Type: application/json' -X POST \
  http://localhost:9096/openmrs/ws/rest/v1/alert \
  -d "{
    \"text\": \"{\\\"v\\\":1,\\\"type\\\":\\\"REFERRAL_ARRIVED\\\",\\\"severity\\\":\\\"INFO\\\",\\\"title\\\":\\\"New referral received\\\",\\\"body\\\":\\\"Patient ABC routed for Cardiology\\\",\\\"link\\\":\\\"/openmrs/spa/patient/<patientUuid>/chart/Referrals\\\"}\",
    \"satisfiedByAny\": false,
    \"alertRead\": false,
    \"dateToExpire\": \"2026-05-14T23:59:59.000+0000\",
    \"recipients\": [{\"recipient\": \"$ADMIN_UUID\"}]
  }"
```

Within 30 seconds (the ESM's poll interval), the receiving user should see:

- Bell badge increments
- Top-right toast (blue bar for INFO, red for CRITICAL)
- Clicking the toast **Open** button or the bell row navigates to the patient's
  Referrals tab

---

## 11. Quick reference card

```
Endpoint:  POST {openmrs.rest.baseUrl}/alert
Auth:      Basic auth, service user with `Manage Alerts`
Body:      {
             "text": "{\"v\":1,\"severity\":\"INFO\",\"title\":\"...\",\"body\":\"...\",\"link\":\"/openmrs/spa/patient/<uuid>/chart/Referrals\"}",
             "satisfiedByAny": false,
             "alertRead": false,
             "dateToExpire": "<now + 48h ISO-8601>",
             "recipients": [{"recipient": "<user-uuid>"}, ...]
           }
Limits:    text ≤ 512 chars; recipients ≥ 1
On error:  log + swallow; never fail the referral
```
