package com.uom.lims.dispatch;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LabReportPdfServiceTest {

    @Test
    void generatesReadableAuthorizedReportAndPreviewArtifact() throws Exception {
        LabReportData report = sampleReport();
        byte[] pdf = service().generate(report);

        assertThat(pdf).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
        }

        Path preview = Path.of("build", "generated-reports", "sample-lab-report.pdf");
        Files.createDirectories(preview.getParent());
        Files.write(preview, pdf);
        assertThat(Files.size(preview)).isGreaterThan(2_000);
    }

    /**
     * The identity a patient reads is the report number, not the anchor UUID that
     * used to be printed under "Report ID".
     */
    @Test
    void printsTheReportNumberAndNeverTheAnchorUuid() throws Exception {
        String text = textOf(service().generate(sampleReport()));

        assertThat(text).contains("REP2026-00042");
        assertThat(text).doesNotContain("d87a4b51-3230-45a4-a0c9-0b9dcbbfa742");
    }

    /** The fields a clinical report has to carry to be interpretable on its own. */
    @Test
    void carriesTheIdentificationAClinicalReportRequires() throws Exception {
        String text = textOf(service().generate(sampleReport()));

        assertThat(text)
                .contains("Kalana Sandakelum")
                .contains("PAT-000184")
                .contains("RES2026-00042")
                .contains("SMP-20260816-0042")
                .contains("Dr. A. Perera")
                .contains("Dr. N. Fernando, Consultant Pathologist")
                .contains("Reference interval")
                .contains("End of report");

        // The total is drawn as a separate template (it is not known until the
        // document closes), so extraction reports it without the intervening space
        // the reader sees. What matters is that the count is the real one — this is
        // what caught the footer claiming "of 2" on a single-page report.
        assertThat(text).containsPattern("Page 1 of\\s*1");
    }

    /** Age is taken at collection, not at printing: 1999-06-14 to 2026-08-16 is 27. */
    @Test
    void statesAgeAtCollectionRatherThanAtPrinting() throws Exception {
        assertThat(textOf(service().generate(sampleReport()))).contains("27 y / MALE");
    }

    /** A monochrome printout must still say which rows are out of range. */
    @Test
    void namesAbnormalAndCriticalRowsInWordsNotOnlyInColour() throws Exception {
        String text = textOf(service().generate(sampleReport()));

        assertThat(text).contains("HIGH");
        assertThat(text).contains("CRITICAL LOW");
    }

    /** A report with no rows still renders rather than throwing at the patient. */
    @Test
    void rendersWhenTheResultSetIsEmpty() throws Exception {
        LabReportData full = sampleReport();
        LabReportData empty = new LabReportData(
                full.reportReference(), full.reportNumber(), full.branchCode(), full.patientCode(),
                full.patientName(), null, null, null, null, full.testPanel(), null, null, null, null,
                null, full.authorizedAt(), full.authorizedBy(), null, List.of());

        String text = textOf(service().generate(empty));

        assertThat(text).contains("REP2026-00042").contains("End of report");
    }

    private static LabReportPdfService service() {
        return new LabReportPdfService();
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PdfReader reader = new PdfReader(pdf)) {
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(new PdfTextExtractor(reader).getTextFromPage(page)).append('\n');
            }
            return text.toString();
        }
    }

    public static LabReportData sampleReport() {
        return new LabReportData(
                "d87a4b51-3230-45a4-a0c9-0b9dcbbfa742",
                "REP2026-00042",
                "BR001",
                "PAT-000184",
                "Kalana Sandakelum",
                LocalDate.of(1999, 6, 14),
                "MALE",
                "Dr. A. Perera",
                "Outpatient Department",
                "Full Blood Count",
                "SMP-20260816-0042",
                "RES2026-00042",
                "EDTA",
                "ROUTINE",
                OffsetDateTime.of(2026, 8, 16, 13, 20, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
                OffsetDateTime.of(2026, 8, 16, 14, 25, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
                "Dr. N. Fernando, Consultant Pathologist",
                "Mild neutrophilia. Correlate with the patient's clinical findings.",
                List.of(
                        new LabReportData.ResultRow("White Blood Cell Count", "12.8", "10^9/L", "4.0 - 11.0", "HIGH"),
                        new LabReportData.ResultRow("Red Blood Cell Count", "5.1", "10^12/L", "4.5 - 5.9", "NORMAL"),
                        new LabReportData.ResultRow("Haemoglobin", "14.6", "g/dL", "13.5 - 17.5", "NORMAL"),
                        new LabReportData.ResultRow("Haematocrit", "44.2", "%", "41.0 - 53.0", "NORMAL"),
                        new LabReportData.ResultRow("Platelet Count", "245", "10^9/L", "150 - 400", "NORMAL"),
                        new LabReportData.ResultRow("Haematocrit (repeat)", "19.0", "%", "41.0 - 53.0",
                                "CRITICAL_LOW")));
    }
}
