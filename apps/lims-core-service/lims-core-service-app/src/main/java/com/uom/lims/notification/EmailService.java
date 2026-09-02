package com.uom.lims.notification;

import com.uom.lims.dispatch.LabReportData;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * F2: SMTP sends are wrapped in a retry + circuit-breaker ("smtp"). The SMTP socket
 * timeouts (application.yml) bound each attempt; the breaker fails fast during an
 * outage. Fallbacks rethrow a consistent RuntimeException — every caller already
 * tolerates that (logs / records a FAILED attempt / returns false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.verification.base-url}")
    private String baseUrl;

    @Value("${app.reports.portal-url:http://localhost:3000/patient-portal/orders}")
    private String reportsPortalUrl = "http://localhost:3000/patient-portal/orders";

    @Retry(name = "smtp", fallbackMethod = "sendVerificationEmailFallback")
    @CircuitBreaker(name = "smtp")
    public void sendVerificationEmail(String toEmail, String patientName, String rawToken) {

        String verificationLink = baseUrl + "/api/v1/patients/verify-email?token=" + rawToken;

        try {
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Verify Your Email - LIMS");

            String htmlContent = generateVerificationEmailHtml(patientName, verificationLink);
            helper.setText(htmlContent, true);

            mailSender.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new RuntimeException("Failed to send verification email to " + toEmail, e);
        }
    }

    @Retry(name = "smtp", fallbackMethod = "sendLabReportEmailFallback")
    @CircuitBreaker(name = "smtp")
    public void sendLabReportEmail(String toEmail, LabReportData report, byte[] reportPdf) {
        try {
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(
                    message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Durdans Laboratory Report - " + display(report.testPanel())
                    + " - " + display(report.patientName()));
            helper.setText(generateLabReportEmailHtml(report), true);
            helper.addAttachment(reportFilename(report), new ByteArrayResource(reportPdf), "application/pdf");
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new RuntimeException("Failed to send lab report email to " + toEmail, e);
        }
    }

    /**
     * Sends a plain notification email (used by the critical-value callback, H1). Kept
     * generic so the caller controls subject/body; throws so the caller can record a
     * failed attempt and retry/escalate.
     */
    @Retry(name = "smtp", fallbackMethod = "sendNotificationEmailFallback")
    @CircuitBreaker(name = "smtp")
    public void sendNotificationEmail(String toEmail, String subject, String bodyHtml) {
        try {
            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper =
                    new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(bodyHtml, true);
            mailSender.send(message);
        } catch (jakarta.mail.MessagingException e) {
            throw new RuntimeException("Failed to send notification email to " + toEmail, e);
        }
    }

    private String generateVerificationEmailHtml(String patientName, String verificationLink) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { font-family: 'Inter', sans-serif; background-color: #f6f7f8; margin: 0; padding: 0; }
                        .container { max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1); }
                        .header { background-color: #101922; padding: 24px; text-align: center; }
                        .header h1 { color: #ffffff; margin: 0; font-size: 24px; font-weight: 700; }
                        .header span { color: #137fec; }
                        .content { padding: 40px 32px; color: #1e293b; }
                        .greeting { font-size: 18px; font-weight: 600; margin-bottom: 24px; }
                        .message { font-size: 16px; line-height: 1.6; color: #475569; margin-bottom: 32px; }
                        .button-container { text-align: center; margin: 32px 0; }
                        .button { background-color: #137fec; color: #ffffff !important; padding: 14px 32px; text-decoration: none; border-radius: 6px; font-weight: 600; display: inline-block; font-size: 16px; }
                        .footer { background-color: #f8fafc; padding: 24px; text-align: center; font-size: 12px; color: #94a3b8; border-top: 1px solid #e2e8f0; }
                        .link-text { font-size: 12px; color: #94a3b8; margin-top: 24px; word-break: break-all; }
                        a.raw-link { color: #137fec; text-decoration: none; }
                    </style>
                </head>
                <body>
                    <div style="padding: 40px 0;">
                        <div class="container">
                            <div class="header">
                                <h1>DURDANS <span>ERP</span></h1>
                            </div>
                            <div class="content">
                                <div class="greeting">Dear %s,</div>
                                <div class="message">
                                    Thank you for registering with Durdans Hospital Patient Management System.
                                    To ensure the security of your account and access all features, please verify your email address.
                                </div>
                                <div class="button-container">
                                    <a href="%s" class="button" style="color: #ffffff !important;">Verify Email Address</a>
                                </div>
                                <div class="message">
                                    This link will expire in 24 hours. If you did not create an account, no further action is required.
                                </div>
                                <div class="link-text">
                                    If the button above doesn't work, copy and paste this link into your browser:<br>
                                    <a href="%s" class="raw-link">%s</a>
                                </div>
                            </div>
                            <div class="footer">
                                &copy; %d Durdans Hospital. All Rights Reserved.<br>
                                This is an automated message, please do not reply.
                            </div>
                        </div>
                    </div>
                </body>
                </html>
                """
                .formatted(patientName, verificationLink, verificationLink, verificationLink,
                        java.time.Year.now().getValue());
    }

    // ---- F2 fallbacks: surface a consistent failure when retries are exhausted or the
    //      breaker is open. Callers already handle a thrown RuntimeException. ----

    @SuppressWarnings("unused")
    private void sendVerificationEmailFallback(String toEmail, String patientName, String rawToken, Throwable t) {
        throw emailUnavailable(toEmail, t);
    }

    @SuppressWarnings("unused")
    private void sendLabReportEmailFallback(String toEmail, LabReportData report, byte[] reportPdf, Throwable t) {
        throw emailUnavailable(toEmail, t);
    }

    @SuppressWarnings("unused")
    private void sendNotificationEmailFallback(String toEmail, String subject, String bodyHtml, Throwable t) {
        throw emailUnavailable(toEmail, t);
    }

    private RuntimeException emailUnavailable(String toEmail, Throwable t) {
        log.warn("Email delivery to {} unavailable (retry/breaker): {}", toEmail, t.toString());
        return new RuntimeException("Email delivery unavailable (circuit open or retries exhausted)", t);
    }

    private String generateLabReportEmailHtml(LabReportData report) {
        StringBuilder rows = new StringBuilder();
        for (LabReportData.ResultRow row : report.results()) {
            String flagColor = row.abnormal() ? "#8a1f1f" : "#3d5a45";
            String flagWeight = row.abnormal() ? "bold" : "normal";
            rows.append("""
                    <tr>
                      <td style="padding:9px 12px;border-bottom:1px solid #dde3ea">%s</td>
                      <td style="padding:9px 12px;border-bottom:1px solid #dde3ea">%s</td>
                      <td style="padding:9px 12px;border-bottom:1px solid #dde3ea;color:#5b6672">%s</td>
                      <td style="padding:9px 12px;border-bottom:1px solid #dde3ea;color:#5b6672">%s</td>
                      <td style="padding:9px 12px;border-bottom:1px solid #dde3ea;color:%s;font-weight:%s">%s</td>
                    </tr>
                    """.formatted(
                    html(row.parameter()), html(row.value()), html(row.unit()),
                    html(row.referenceRange()), flagColor, flagWeight, html(label(row.flag()))));
        }
        if (rows.isEmpty()) {
            rows.append("<tr><td colspan=\"5\" style=\"padding:14px 12px;color:#5b6672\">"
                    + "The detailed results are provided in the attached PDF report.</td></tr>");
        }

        String clinicalNote = report.clinicalNote() == null || report.clinicalNote().isBlank()
                ? ""
                : "<p style=\"margin:18px 0 0;font-size:14px;line-height:1.6;color:#333e4c\">"
                + "<span style=\"color:#5b6672\">Clinical note:</span> " + html(report.clinicalNote()) + "</p>";

        return """
                <!doctype html>
                <html><body style="margin:0;padding:0;background:#eef1f4;font-family:Georgia,'Times New Roman',serif;color:#26303b">
                  <div style="padding:24px 12px">
                    <div style="max-width:680px;margin:auto;background:#ffffff;border:1px solid #d6dde4">
                      <div style="padding:26px 36px 20px;border-bottom:3px solid #1f3a5f">
                        <div style="font-size:19px;letter-spacing:2px;color:#1f3a5f">DURDANS HOSPITAL</div>
                        <div style="font-size:12px;letter-spacing:1px;color:#5b6672;margin-top:2px">LABORATORY SERVICES</div>
                      </div>
                      <div style="padding:28px 36px">
                        <p style="margin:0 0 18px;font-size:15px;line-height:1.7">Dear %s,</p>
                        <p style="margin:0 0 18px;font-size:15px;line-height:1.7">
                          Your laboratory report for <strong>%s</strong> has been reviewed and clinically
                          authorized. The complete report is attached to this email as a PDF document.
                        </p>

                        <table role="presentation" style="width:100%%;margin:6px 0 22px;border-collapse:collapse;font-size:13px;font-family:Arial,Helvetica,sans-serif">
                          <tr>
                            <td style="padding:7px 0;color:#5b6672;width:130px;border-bottom:1px solid #e6ebf0">Patient ID</td>
                            <td style="padding:7px 0;border-bottom:1px solid #e6ebf0">%s</td>
                            <td style="padding:7px 0;color:#5b6672;width:110px;border-bottom:1px solid #e6ebf0">Sample</td>
                            <td style="padding:7px 0;border-bottom:1px solid #e6ebf0">%s</td>
                          </tr>
                          <tr>
                            <td style="padding:7px 0;color:#5b6672;border-bottom:1px solid #e6ebf0">Report no.</td>
                            <td style="padding:7px 0;border-bottom:1px solid #e6ebf0">%s</td>
                            <td style="padding:7px 0;color:#5b6672;border-bottom:1px solid #e6ebf0">Authorized</td>
                            <td style="padding:7px 0;border-bottom:1px solid #e6ebf0">%s</td>
                          </tr>
                        </table>

                        <div style="font-size:12px;letter-spacing:1px;color:#1f3a5f;margin:0 0 8px;font-family:Arial,Helvetica,sans-serif"><strong>LABORATORY RESULTS</strong></div>
                        <table style="width:100%%;border-collapse:collapse;font-size:13px;font-family:Arial,Helvetica,sans-serif;border:1px solid #dde3ea">
                          <thead><tr style="background:#f4f6f8;text-align:left;color:#1f3a5f">
                            <th style="padding:9px 12px;font-weight:bold;border-bottom:1px solid #c9d2db">Parameter</th>
                            <th style="padding:9px 12px;font-weight:bold;border-bottom:1px solid #c9d2db">Result</th>
                            <th style="padding:9px 12px;font-weight:bold;border-bottom:1px solid #c9d2db">Unit</th>
                            <th style="padding:9px 12px;font-weight:bold;border-bottom:1px solid #c9d2db">Reference range</th>
                            <th style="padding:9px 12px;font-weight:bold;border-bottom:1px solid #c9d2db">Flag</th>
                          </tr></thead><tbody>%s</tbody>
                        </table>
                        %s
                        <p style="margin:22px 0 0;font-size:14px;line-height:1.7">
                          You can also view your reports at any time in the
                          <a href="%s" style="color:#1f3a5f">Durdans patient portal</a>.
                          Please consult your doctor for the clinical interpretation of these results.
                        </p>
                        <p style="margin:26px 0 0;font-size:14px;line-height:1.6">
                          Yours sincerely,<br>
                          <strong>%s</strong><br>
                          <span style="font-size:12px;color:#5b6672">Consultant, Laboratory Services &mdash; electronically authorized, %s</span>
                        </p>
                      </div>
                      <div style="padding:16px 36px;border-top:1px solid #d6dde4;color:#7b8590;font-size:11px;line-height:1.6;font-family:Arial,Helvetica,sans-serif">
                        This message and its attachment are confidential and intended solely for the named recipient.
                        If you have received it in error, please delete it and notify the laboratory.
                        This is an automated message; please do not reply.
                      </div>
                    </div>
                  </div>
                </body></html>
                """.formatted(
                html(report.patientName()), html(report.testPanel()), html(report.patientCode()),
                html(report.sampleBarcode()),
                html(firstPresent(report.reportNumber(), report.reportReference())),
                format(report.authorizedAt()),
                rows, clinicalNote, HtmlUtils.htmlEscape(reportsPortalUrl),
                html(report.authorizedBy()), format(report.authorizedAt()));
    }

    /**
     * {@code REP2026-00042_Ruwan_Jayasinghe.pdf}. What lands in the patient's inbox
     * used to be {@code Durdans-Lab-Report-6cdc83c3-7a08-4f4a-87da-ad4fa149e1ad.pdf}
     * — a name nobody can file, search for or tell apart from the next one. Number
     * first so a folder of reports sorts chronologically, then the patient's name,
     * because a household shares an inbox.
     *
     * <p>Everything outside {@code [A-Za-z0-9._-]} is folded to an underscore: this
     * string becomes a filename on the recipient's machine, and a name carrying a
     * slash, a quote or a control character has no business getting there.
     */
    static String reportFilename(LabReportData report) {
        String number = slug(firstPresent(report.reportNumber(), report.reportReference()));
        String name = slug(report.patientName());
        return number + "_" + name + ".pdf";
    }

    /** Collapses a free-text field to a safe, readable filename segment. */
    private static String slug(String value) {
        String cleaned = display(value)
                .replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[._-]+|[._-]+$", "");
        return cleaned.isBlank() ? "Report" : cleaned;
    }

    private static String firstPresent(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String html(String value) {
        return HtmlUtils.htmlEscape(display(value));
    }

    private static String label(String value) {
        return display(value).replace('_', ' ');
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "Not recorded" : value.trim();
    }

    private static String format(java.time.OffsetDateTime value) {
        return value == null ? "Not recorded"
                : value.format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.UK));
    }
}
