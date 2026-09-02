package com.uom.lims.dispatch;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders an authorized result set as the laboratory's patient-facing PDF report.
 *
 * <p>The layout follows what a clinical report is expected to carry (ISO 15189
 * §7.4.1.6 / CLIA §493.1291), because this document leaves the lab and is read
 * by someone who cannot ask it a follow-up question:
 *
 * <ul>
 *   <li><b>Identification of the laboratory</b> and of the report itself, by a
 *       number a person can quote — {@code REP2026-00042}, not the anchor
 *       result's UUID, which is what used to be printed here.</li>
 *   <li><b>Unambiguous patient identification</b>: name, hospital number, date of
 *       birth <em>and</em> age at collection, sex. Two identifiers minimum, which
 *       is what lets a reader be sure the report in their hand is theirs.</li>
 *   <li><b>Specimen provenance</b>: case number, container, priority, and the
 *       collection time — the report is only interpretable against when the
 *       sample was taken, not when it was printed.</li>
 *   <li><b>Results with units and the reference interval they were judged
 *       against</b>, abnormality flagged in words as well as colour so the
 *       report survives a monochrome printer and a colour-blind reader.</li>
 *   <li><b>Identity of the authorizing person</b> and the time of authorization.</li>
 *   <li><b>Page x of y</b> and an explicit end-of-report marker, so a reader
 *       holding an incomplete printout can tell that is what they have.</li>
 * </ul>
 *
 * <p>Every timestamp arrives already fixed to Asia/Colombo by
 * {@link LabReportDataService}, so this class never consults the system zone.
 */
@Service
public class LabReportPdfService {

    private static final Color NAVY = new Color(11, 31, 58);
    private static final Color TEXT = new Color(37, 47, 59);
    private static final Color MUTED = new Color(108, 121, 136);
    private static final Color BLUE = new Color(19, 127, 236);
    private static final Color RULE = new Color(205, 214, 224);
    private static final Color HAIRLINE = new Color(228, 234, 240);
    private static final Color BAND = new Color(246, 248, 251);
    private static final Color ABNORMAL_BG = new Color(255, 247, 237);
    private static final Color CRITICAL_BG = new Color(254, 235, 236);
    private static final Color CRITICAL_FG = new Color(159, 26, 33);
    private static final Color ABNORMAL_FG = new Color(146, 74, 12);
    private static final Color NORMAL_FG = new Color(38, 92, 63);

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.UK);
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK);

    @Value("${app.reports.laboratory-name:Durdans Hospital Laboratory Services}")
    private String laboratoryName = "Durdans Hospital Laboratory Services";

    @Value("${app.reports.laboratory-address:No. 3, Alfred Place, Colombo 03, Sri Lanka}")
    private String laboratoryAddress = "No. 3, Alfred Place, Colombo 03, Sri Lanka";

    @Value("${app.reports.laboratory-contact:+94 11 214 0000  |  laboratory@durdans.com}")
    private String laboratoryContact = "+94 11 214 0000  |  laboratory@durdans.com";

    public byte[] generate(LabReportData report) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 40, 40, 38, 62);
            PdfWriter writer = PdfWriter.getInstance(document, output);

            String reportNo = reference(report);
            document.addTitle("Durdans Laboratory Report " + reportNo);
            document.addAuthor(laboratoryName);
            document.addSubject(value(report.testPanel()));
            document.addKeywords(reportNo + ", " + value(report.patientCode()));

            // Page x of y and the per-page footer identity: a loose page must still
            // say which report and which patient it belongs to.
            writer.setPageEvent(new PageFurniture(reportNo, value(report.patientName())));

            document.open();
            addLetterhead(document, report);
            addPatientAndSpecimen(document, report);
            addResults(document, report);
            addClinicalNote(document, report);
            addAuthorization(document, report);
            addEndMarker(document);
            document.close();
            return output.toByteArray();
        } catch (DocumentException | java.io.IOException ex) {
            throw new IllegalStateException("Could not generate laboratory report PDF", ex);
        }
    }

    // ---------------------------------------------------------------- letterhead

    private void addLetterhead(Document document, LabReportData report) throws DocumentException {
        PdfPTable head = new PdfPTable(new float[]{3.4f, 1.6f});
        head.setWidthPercentage(100);

        PdfPCell brand = borderless();
        Paragraph hospital = new Paragraph("DURDANS HOSPITAL", bold(16, NAVY));
        hospital.setSpacingAfter(2);
        brand.addElement(hospital);
        brand.addElement(new Paragraph(laboratoryName, font(9.5f, BLUE)));
        brand.addElement(new Paragraph(laboratoryAddress, font(8, MUTED)));
        brand.addElement(new Paragraph(laboratoryContact, font(8, MUTED)));
        head.addCell(brand);

        PdfPCell status = borderless();
        status.setHorizontalAlignment(Element.ALIGN_RIGHT);
        status.addElement(rightAligned("FINAL REPORT", bold(10, NAVY)));
        status.addElement(rightAligned("Clinically authorized", font(8.5f, NORMAL_FG)));
        status.addElement(rightAligned(value(report.branchCode()) + " branch", font(8, MUTED)));
        head.addCell(status);
        document.add(head);

        document.add(rule(1.4f, NAVY, 10, 0));

        // The report number reads as the document's name, not as one field among
        // sixteen — it is what a patient quotes on the phone.
        PdfPTable identity = new PdfPTable(new float[]{2.3f, 1.5f, 1.6f});
        identity.setWidthPercentage(100);
        identity.setSpacingBefore(8);
        identity.setSpacingAfter(4);
        identity.addCell(stacked("REPORT NO.", reference(report), bold(12, NAVY)));
        identity.addCell(stacked("TEST PANEL", value(report.testPanel()), bold(10, TEXT)));
        identity.addCell(stacked("AUTHORIZED", format(report.authorizedAt()), bold(10, TEXT)));
        document.add(identity);

        document.add(rule(0.7f, RULE, 2, 12));
    }

    // ------------------------------------------------------- patient / specimen

    private void addPatientAndSpecimen(Document document, LabReportData report) throws DocumentException {
        document.add(sectionTitle("PATIENT"));
        PdfPTable patient = fieldGrid();
        addField(patient, "Name", value(report.patientName()));
        addField(patient, "Patient ID", value(report.patientCode()));
        addField(patient, "Date of birth", report.patientDob() == null
                ? "Not recorded" : DATE_ONLY.format(report.patientDob()));
        addField(patient, "Age / Sex", ageAndSex(report));
        addField(patient, "Referring doctor", value(report.referringDoctor()));
        addField(patient, "Department", value(report.referringDepartment()));
        document.add(patient);

        document.add(sectionTitle("SPECIMEN"));
        PdfPTable specimen = fieldGrid();
        addField(specimen, "Case no.", value(report.caseNumber()));
        addField(specimen, "Barcode", value(report.sampleBarcode()));
        addField(specimen, "Container", label(report.specimenType()));
        addField(specimen, "Priority", label(report.priority()));
        addField(specimen, "Collected", format(report.collectedAt()));
        addField(specimen, "Reported", format(report.authorizedAt()));
        document.add(specimen);
    }

    /**
     * Age is computed at collection, not at printing. A report reprinted a year
     * later must still read against the patient the sample came from — and for
     * paediatric reference intervals the difference is the whole interpretation.
     */
    private static String ageAndSex(LabReportData report) {
        String sex = label(report.patientGender());
        LocalDate dob = report.patientDob();
        OffsetDateTime reference = report.collectedAt() == null ? report.authorizedAt() : report.collectedAt();
        if (dob == null || reference == null) {
            return sex;
        }
        int years = Period.between(dob, reference.toLocalDate()).getYears();
        return years + " y / " + sex;
    }

    // ------------------------------------------------------------------ results

    private void addResults(Document document, LabReportData report) throws DocumentException {
        document.add(sectionTitle("RESULTS"));

        PdfPTable table = new PdfPTable(new float[]{2.9f, 1.25f, 1.0f, 1.75f, 1.35f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingAfter(8);

        String[] headings = {"Investigation", "Result", "Unit", "Reference interval", "Flag"};
        int[] alignment = {Element.ALIGN_LEFT, Element.ALIGN_RIGHT, Element.ALIGN_LEFT,
                Element.ALIGN_LEFT, Element.ALIGN_CENTER};
        for (int i = 0; i < headings.length; i++) {
            PdfPCell header = new PdfPCell(new Phrase(headings[i], bold(8, NAVY)));
            header.setBackgroundColor(BAND);
            header.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
            header.setBorderColor(RULE);
            header.setBorderWidth(0.7f);
            header.setPadding(6);
            header.setPaddingLeft(8);
            header.setPaddingRight(8);
            header.setHorizontalAlignment(alignment[i]);
            table.addCell(header);
        }

        if (report.results().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase(
                    "No result rows were available for this report. Please contact the laboratory.",
                    font(9, MUTED)));
            empty.setColspan(5);
            empty.setBorder(Rectangle.BOTTOM);
            empty.setBorderColor(HAIRLINE);
            empty.setPadding(14);
            table.addCell(empty);
        } else {
            for (LabReportData.ResultRow row : report.results()) {
                Severity severity = Severity.of(row);
                table.addCell(resultCell(value(row.parameter()), severity, Element.ALIGN_LEFT,
                        severity == Severity.NORMAL ? font(9, TEXT) : bold(9, TEXT)));
                table.addCell(resultCell(value(row.value()), severity, Element.ALIGN_RIGHT,
                        severity == Severity.NORMAL ? font(9.5f, TEXT) : bold(9.5f, severity.foreground)));
                table.addCell(resultCell(value(row.unit()), severity, Element.ALIGN_LEFT, font(9, MUTED)));
                table.addCell(resultCell(value(row.referenceRange()), severity, Element.ALIGN_LEFT,
                        font(9, MUTED)));
                table.addCell(resultCell(label(row.flag()), severity, Element.ALIGN_CENTER,
                        severity == Severity.NORMAL ? font(8.5f, NORMAL_FG) : bold(8.5f, severity.foreground)));
            }
        }
        document.add(table);

        // Spelled out because the colours do not survive a fax, a photocopier or a
        // reader who cannot distinguish them.
        Paragraph legend = new Paragraph(
                "Flags — HIGH / LOW: outside the reference interval.  "
                        + "CRITICAL HIGH / CRITICAL LOW: outside the interval by a margin that warrants "
                        + "immediate clinical attention; the requesting clinician is notified separately.",
                font(7.5f, MUTED));
        legend.setSpacingAfter(4);
        document.add(legend);
    }

    /** How far outside the interval a row sits — drives its tint and weight. */
    private enum Severity {
        NORMAL(Color.WHITE, TEXT),
        ABNORMAL(ABNORMAL_BG, ABNORMAL_FG),
        CRITICAL(CRITICAL_BG, CRITICAL_FG);

        private final Color background;
        private final Color foreground;

        Severity(Color background, Color foreground) {
            this.background = background;
            this.foreground = foreground;
        }

        static Severity of(LabReportData.ResultRow row) {
            if (!row.abnormal()) {
                return NORMAL;
            }
            String flag = row.flag() == null ? "" : row.flag().toUpperCase(Locale.ROOT);
            return flag.contains("CRITICAL") || flag.contains("PANIC") ? CRITICAL : ABNORMAL;
        }
    }

    private static PdfPCell resultCell(String text, Severity severity, int alignment, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(severity.background);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(HAIRLINE);
        cell.setBorderWidth(0.6f);
        cell.setPadding(6);
        cell.setPaddingLeft(8);
        cell.setPaddingRight(8);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    // ------------------------------------------------------- note / authorization

    private void addClinicalNote(Document document, LabReportData report) throws DocumentException {
        if (report.clinicalNote() == null || report.clinicalNote().isBlank()) {
            return;
        }
        document.add(sectionTitle("COMMENTS"));
        PdfPTable note = new PdfPTable(1);
        note.setWidthPercentage(100);
        note.setSpacingAfter(14);
        PdfPCell cell = new PdfPCell(new Phrase(report.clinicalNote().trim(), font(9, TEXT)));
        cell.setBackgroundColor(BAND);
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderColorLeft(BLUE);
        cell.setBorderWidthLeft(2.5f);
        cell.setPadding(10);
        note.addCell(cell);
        document.add(note);
    }

    private void addAuthorization(Document document, LabReportData report) throws DocumentException {
        document.add(rule(0.7f, RULE, 6, 10));

        PdfPTable block = new PdfPTable(new float[]{3.1f, 2.2f});
        block.setWidthPercentage(100);

        PdfPCell advice = borderless();
        advice.addElement(new Paragraph(
                "Results must be interpreted together with the clinical findings, the medical "
                        + "history and any concurrent therapy. Reference intervals apply to the "
                        + "method and population stated by this laboratory and are not "
                        + "transferable between laboratories.", font(7.5f, MUTED)));
        advice.addElement(spacer(4));
        advice.addElement(new Paragraph(
                "This report was produced and authorized electronically and is valid without a "
                        + "handwritten signature. It is confidential and intended solely for the "
                        + "named patient and their treating clinician.", font(7.5f, MUTED)));
        block.addCell(advice);

        PdfPCell sign = borderless();
        sign.setHorizontalAlignment(Element.ALIGN_RIGHT);
        sign.addElement(rightAligned("Electronically authorized by", font(8, MUTED)));
        sign.addElement(spacer(2));
        sign.addElement(rightAligned(value(report.authorizedBy()), bold(10.5f, NAVY)));
        sign.addElement(rightAligned("Consultant, Laboratory Services", font(8, TEXT)));
        sign.addElement(rightAligned(format(report.authorizedAt()), font(8, MUTED)));
        block.addCell(sign);
        document.add(block);
    }

    /**
     * Without this a reader has no way to distinguish a complete report from one
     * whose last page did not come out of the printer.
     */
    private void addEndMarker(Document document) throws DocumentException {
        Paragraph end = new Paragraph("— End of report —", font(8, MUTED));
        end.setAlignment(Element.ALIGN_CENTER);
        end.setSpacingBefore(16);
        document.add(end);
    }

    // ------------------------------------------------------------------ furniture

    /**
     * Draws the running footer. Total page count is only known once the document
     * is closed, so the "of N" is reserved as a template on every page and filled
     * in at the end.
     */
    private static final class PageFurniture extends PdfPageEventHelper {

        /** Room reserved for the total page count, filled in once it is known. */
        private static final float TOTAL_PAGES_WIDTH = 20f;

        private final String reportNo;
        private final String patientName;
        private PdfTemplate totalPages;

        private PageFurniture(String reportNo, String patientName) {
            this.reportNo = reportNo;
            this.patientName = patientName;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPages = writer.getDirectContent().createTemplate(TOTAL_PAGES_WIDTH, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            float left = document.leftMargin();
            float right = page.getWidth() - document.rightMargin();
            float baseline = document.bottomMargin() - 16;

            PdfContentByte canvas = writer.getDirectContent();
            canvas.setColorStroke(RULE);
            canvas.setLineWidth(0.6f);
            canvas.moveTo(left, baseline + 14);
            canvas.lineTo(right, baseline + 14);
            canvas.stroke();

            Phrase identity = new Phrase(
                    reportNo + "   ·   " + patientName + "   ·   Confidential patient report",
                    font(7.5f, MUTED));
            com.lowagie.text.pdf.ColumnText.showTextAligned(
                    canvas, Element.ALIGN_LEFT, identity, left, baseline, 0);

            // "Page 2 of 3" is laid out as text plus a placeholder: the total is not
            // known until the document closes. Measure the text so the pair as a whole
            // ends flush with the right margin.
            Font footerFont = font(7.5f, MUTED);
            String prefix = "Page " + writer.getPageNumber() + " of ";
            float prefixWidth = footerFont.getCalculatedBaseFont(true).getWidthPoint(prefix, 7.5f);
            float start = right - prefixWidth - TOTAL_PAGES_WIDTH;
            com.lowagie.text.pdf.ColumnText.showTextAligned(
                    canvas, Element.ALIGN_LEFT, new Phrase(prefix, footerFont), start, baseline, 0);
            canvas.addTemplate(totalPages, start + prefixWidth, baseline);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalPages.beginText();
            totalPages.setFontAndSize(font(7.5f, MUTED).getCalculatedBaseFont(true), 7.5f);
            totalPages.setColorFill(MUTED);
            totalPages.setTextMatrix(0, 0);
            // getPageNumber() is the number of the page that would come next, so on a
            // closed document it is one past the last one actually written.
            totalPages.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPages.endText();
        }
    }

    // -------------------------------------------------------------------- pieces

    private static Paragraph sectionTitle(String text) {
        Chunk chunk = new Chunk(text, bold(8, NAVY));
        chunk.setCharacterSpacing(0.9f);
        Paragraph title = new Paragraph(chunk);
        title.setSpacingBefore(6);
        title.setSpacingAfter(6);
        return title;
    }

    /** Two label/value pairs per row, so the block stays scannable at a glance. */
    private static PdfPTable fieldGrid() {
        PdfPTable table = new PdfPTable(new float[]{1.15f, 2.0f, 1.15f, 2.0f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(12);
        return table;
    }

    private static void addField(PdfPTable table, String label, String value) {
        PdfPCell key = new PdfPCell(new Phrase(label, font(8, MUTED)));
        key.setBorder(Rectangle.BOTTOM);
        key.setBorderColor(HAIRLINE);
        key.setBorderWidth(0.6f);
        key.setPadding(5);
        key.setPaddingLeft(0);
        table.addCell(key);

        PdfPCell data = new PdfPCell(new Phrase(value, font(9, TEXT)));
        data.setBorder(Rectangle.BOTTOM);
        data.setBorderColor(HAIRLINE);
        data.setBorderWidth(0.6f);
        data.setPadding(5);
        table.addCell(data);
    }

    private static PdfPCell stacked(String label, String value, Font valueFont) {
        PdfPCell cell = borderless();
        cell.addElement(new Paragraph(label, font(7.5f, MUTED)));
        cell.addElement(spacer(2));
        cell.addElement(new Paragraph(value, valueFont));
        return cell;
    }

    private static PdfPTable rule(float thickness, Color color, float above, float below) {
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        line.setSpacingBefore(above);
        line.setSpacingAfter(below);
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(0.1f);
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColorTop(color);
        cell.setBorderWidthTop(thickness);
        line.addCell(cell);
        return line;
    }

    private static PdfPCell borderless() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static Paragraph rightAligned(String text, Font font) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setAlignment(Element.ALIGN_RIGHT);
        return paragraph;
    }

    private static Paragraph spacer(float height) {
        Paragraph paragraph = new Paragraph(" ", font(height, Color.WHITE));
        paragraph.setLeading(height);
        return paragraph;
    }

    private static Font font(float size, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, Font.NORMAL, color);
    }

    private static Font bold(float size, Color color) {
        return FontFactory.getFont(FontFactory.HELVETICA_BOLD, size, Font.BOLD, color);
    }

    /** The number a person quotes; falls back to the internal key only if unissued. */
    private static String reference(LabReportData report) {
        String number = report.reportNumber();
        return number == null || number.isBlank() ? value(report.reportReference()) : number.trim();
    }

    private static String format(java.time.temporal.TemporalAccessor value) {
        return value == null ? "Not recorded" : DATE_TIME.format(value);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "Not recorded" : value.trim();
    }

    private static String label(String value) {
        return value(value).replace('_', ' ');
    }
}
