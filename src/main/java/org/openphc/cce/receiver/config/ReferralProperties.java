package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the referral-specific flow.
 *
 * When an inbound FHIR ServiceRequest is detected as a referral (see
 * {@code FhirToRestTransformer.isReferral}), the adaptor files it as a "Referral In" encounter in
 * an already-closed intake visit, so the patient has no active visit until they arrive and the
 * clerk starts one.
 *
 * If {@link #enabled} is {@code false}, the adaptor falls back to the generic
 * visit/encounter logic for all ServiceRequests (legacy behaviour).
 */
@Component
@ConfigurationProperties(prefix = "openmrs.referral")
public class ReferralProperties {

    private boolean enabled = true;
    /**
     * Visit type of the closed visit the "Referral In" encounter is filed in (O3's Orders widget
     * needs every order's encounter to have a visit). If blank, the visit type named
     * {@link #intakeVisitTypeName} is used, and created (retired) when it does not exist.
     */
    private String intakeVisitTypeUuid;
    private String intakeVisitTypeName = "Referral Intake";
    /** Department the "Referral In" encounter is recorded at (e.g. Outpatient Clinic). */
    private String locationUuid;
    /**
     * Facility referral visits are held at. If blank, the nearest location at or above
     * {@link #locationUuid} tagged "Visit Location" (as O3 does when starting a visit).
     */
    private String visitLocationUuid;
    private String encounterTypeUuid;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getIntakeVisitTypeUuid() { return intakeVisitTypeUuid; }
    public void setIntakeVisitTypeUuid(String intakeVisitTypeUuid) { this.intakeVisitTypeUuid = intakeVisitTypeUuid; }

    public String getIntakeVisitTypeName() { return intakeVisitTypeName; }
    public void setIntakeVisitTypeName(String intakeVisitTypeName) { this.intakeVisitTypeName = intakeVisitTypeName; }

    public String getVisitLocationUuid() { return visitLocationUuid; }
    public void setVisitLocationUuid(String visitLocationUuid) { this.visitLocationUuid = visitLocationUuid; }

    public String getLocationUuid() { return locationUuid; }
    public void setLocationUuid(String locationUuid) { this.locationUuid = locationUuid; }

    public String getEncounterTypeUuid() { return encounterTypeUuid; }
    public void setEncounterTypeUuid(String encounterTypeUuid) { this.encounterTypeUuid = encounterTypeUuid; }
}
