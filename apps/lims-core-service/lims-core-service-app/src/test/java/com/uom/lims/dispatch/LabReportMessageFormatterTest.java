package com.uom.lims.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabReportMessageFormatterTest {

    @Test
    void formatsPatientTestAndPortalLinkForSms() {
        String message = new LabReportMessageFormatter().formatSms(LabReportPdfServiceTest.sampleReport());

        assertThat(message)
                .startsWith("Durdans Hospital Laboratory\nAuthorized Lab Report Ready\n\nPatient: Kalana Sandakelum")
                .contains("\nTest: Full Blood Count")
                .contains("\nReport No: REP2026-00042")
                .contains("\nStatus: Clinically authorized")
                // The link must be a page this system serves (the patient portal), not a
                // per-report URL on a domain the project does not own.
                .contains("\nView & download your report in the Durdans patient portal:\nhttp://localhost:3000/patient-portal/orders")
                .contains("\nPlease consult your doctor with this report.");
        assertThat(message).doesNotContain("\r").doesNotContain("reports.durdans.com").hasSizeLessThanOrEqualTo(459);
    }

    @Test
    void separatesTheBlocksWithBlankLines() {
        String message = new LabReportMessageFormatter().formatSms(LabReportPdfServiceTest.sampleReport());

        assertThat(message)
                .contains("Authorized Lab Report Ready\n\nPatient:")
                .contains("\n\nView & download your report in the Durdans patient portal:\n")
                .contains("\n\nPlease consult your doctor with this report.");
    }
}
