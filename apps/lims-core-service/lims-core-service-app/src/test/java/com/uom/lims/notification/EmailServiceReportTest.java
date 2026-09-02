package com.uom.lims.notification;

import com.uom.lims.dispatch.LabReportData;
import com.uom.lims.dispatch.LabReportPdfService;
import com.uom.lims.dispatch.LabReportPdfServiceTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceReportTest {

    @Test
    void sendsProfessionalHtmlSummaryAndPdfAttachment() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        EmailService service = new EmailService(sender);
        ReflectionTestUtils.setField(service, "fromEmail", "laboratory@durdans.test");
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:11000");

        LabReportData report = com.uom.lims.dispatch.LabReportPdfServiceTest.sampleReport();
        byte[] pdf = new LabReportPdfService().generate(report);
        service.sendLabReportEmail("patient@example.test", report, pdf);

        verify(sender).send(message);
        assertThat(message.getSubject()).isEqualTo(
                "Durdans Laboratory Report - Full Blood Count - Kalana Sandakelum");
        MimeMultipart multipart = (MimeMultipart) message.getContent();
        assertThat(multipart.getCount()).isGreaterThanOrEqualTo(2);

        boolean foundHtml = false;
        boolean foundPdf = false;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String disposition = part.getDisposition();
            foundHtml = foundHtml || containsText(part.getContent(), "LABORATORY RESULTS");
            if (jakarta.mail.Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                foundPdf = part.getFileName().endsWith(".pdf")
                        && part.getInputStream().readAllBytes().length > 2_000;
                // What the patient sees in their inbox, not a UUID.
                assertThat(part.getFileName()).isEqualTo("REP2026-00042_Kalana_Sandakelum.pdf");
            }
        }
        assertThat(foundHtml).isTrue();
        assertThat(foundPdf).isTrue();
    }

    @Test
    void namesTheAttachmentByReportNumberAndPatient() {
        assertThat(EmailService.reportFilename(LabReportPdfServiceTest.sampleReport()))
                .isEqualTo("REP2026-00042_Kalana_Sandakelum.pdf");
    }

    /**
     * The filename lands on the recipient's filesystem, so anything that could be a
     * path separator, a quote or a control character is folded away — and a report
     * that predates report numbers still gets a usable name rather than none.
     */
    @Test
    void keepsTheFilenameSafeAndNeverEmpty() {
        LabReportData base = LabReportPdfServiceTest.sampleReport();

        LabReportData awkward = withIdentity(base, "REP2026-00042", "O'Brien / Silva, Jr.");
        assertThat(EmailService.reportFilename(awkward))
                .isEqualTo("REP2026-00042_O_Brien_Silva_Jr.pdf");

        LabReportData unnumbered = withIdentity(base, null, "Ruwan Jayasinghe");
        assertThat(EmailService.reportFilename(unnumbered))
                .isEqualTo(base.reportReference() + "_Ruwan_Jayasinghe.pdf");

        LabReportData nameless = withIdentity(base, "REP2026-00042", "   ");
        assertThat(EmailService.reportFilename(nameless)).isEqualTo("REP2026-00042_Not_recorded.pdf");
    }

    private static LabReportData withIdentity(LabReportData base, String reportNumber, String patientName) {
        return new LabReportData(
                base.reportReference(), reportNumber, base.branchCode(), base.patientCode(),
                patientName, base.patientDob(), base.patientGender(), base.referringDoctor(),
                base.referringDepartment(), base.testPanel(), base.sampleBarcode(), base.caseNumber(),
                base.specimenType(), base.priority(), base.collectedAt(), base.authorizedAt(),
                base.authorizedBy(), base.clinicalNote(), base.results());
    }

    private static boolean containsText(Object content, String expected) throws Exception {
        if (content instanceof String text) {
            return text.contains(expected);
        }
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                if (containsText(multipart.getBodyPart(i).getContent(), expected)) return true;
            }
        }
        return false;
    }
}
