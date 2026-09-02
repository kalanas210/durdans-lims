package com.uom.lims.dispatch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Patient-facing "authorized lab report ready" SMS formatter.
 *
 * <p>Formats a clean, professional medical notification containing the patient name,
 * test panel, report reference, clinical authorization status, and a link to the
 * patient portal where the full PDF report can be viewed and downloaded.
 *
 * <p>The link deliberately points at the portal (a page this system serves), not at a
 * per-report URL: an earlier version linked https://reports.durdans.com/r/{ref}, a
 * domain the project does not own, so every patient followed a dead link.
 */
@Component
public class LabReportMessageFormatter {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LabReportMessageFormatter.class);

    // Bounded to 3 concatenated GSM segments to prevent network truncation
    private static final int MAX_SMS_LENGTH = 459;

    @Value("${app.reports.portal-url:http://localhost:3000/patient-portal/orders}")
    private String portalUrl = "http://localhost:3000/patient-portal/orders";

    @Value("${app.sms.provider:mock}")
    private String smsProvider = "mock";

    /**
     * The localhost default only makes sense on a developer machine. A deployed
     * environment MUST set APP_REPORTS_PORTAL_URL to the real portal address —
     * otherwise every patient receives a link their phone cannot open. Sending
     * through a real gateway with a localhost link is loud in the logs for that
     * reason (not fatal: local dev legitimately tests the real gateway).
     */
    @jakarta.annotation.PostConstruct
    void warnIfPortalUrlIsLocal() {
        boolean localUrl = portalUrl != null
                && (portalUrl.contains("localhost") || portalUrl.contains("127.0.0.1"));
        if (localUrl && !"mock".equalsIgnoreCase(smsProvider)) {
            log.warn("app.reports.portal-url is '{}' while SMS provider is '{}'. "
                    + "Patients will receive a link that only opens on this machine — "
                    + "set APP_REPORTS_PORTAL_URL to the public patient-portal URL before deploying.",
                    portalUrl, smsProvider);
        }
    }

    public String formatSms(LabReportData report) {
        StringBuilder message = new StringBuilder("Durdans Hospital Laboratory\n")
                .append("Authorized Lab Report Ready\n")
                .append("\n")
                .append("Patient: ").append(value(report.patientName())).append("\n")
                .append("Test: ").append(value(report.testPanel())).append("\n")
                .append("Report No: ").append(reference(report)).append("\n")
                .append("Status: Clinically authorized\n")
                .append("\n")
                .append("View & download your report in the Durdans patient portal:\n")
                .append(portalUrl).append("\n")
                .append("\n")
                .append("Please consult your doctor with this report.");

        return message.length() <= MAX_SMS_LENGTH
                ? message.toString()
                : message.substring(0, MAX_SMS_LENGTH - 3) + "...";
    }

    /**
     * The number the patient can read back to us. This used to be the first 13
     * characters of the anchor result's UUID — "6CDC83C3-7A08" — which is not a
     * reference anyone can quote, write down, or match against the PDF sitting in
     * their inbox. REP2026-00042 is the same number on the SMS, the email, the
     * attachment's filename and the printout.
     *
     * <p>The UUID truncation survives only as a fallback for reports registered
     * before report numbers existed and never backfilled.
     */
    private static String reference(LabReportData report) {
        String number = report.reportNumber();
        if (number != null && !number.isBlank()) {
            return number.trim();
        }
        String fallback = report.reportReference();
        if (fallback == null || fallback.isBlank()) return "Not available";
        return fallback.length() <= 13 ? fallback : fallback.substring(0, 13).toUpperCase();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "Not recorded" : value.trim();
    }
}
