package com.uom.lims.dispatch;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Immutable, channel-neutral representation of an authorized laboratory report. */
public record LabReportData(
        /** Anchor result id. Internal key — never printed. */
        String reportReference,
        /** {@code REP2026-00042}: what the PDF shows and the attachment is named after. */
        String reportNumber,
        String branchCode,
        String patientCode,
        String patientName,
        LocalDate patientDob,
        String patientGender,
        String referringDoctor,
        String referringDepartment,
        String testPanel,
        String sampleBarcode,
        /** {@code RES2026-00042} — the case number the specimen is known by in the lab. */
        String caseNumber,
        /** Container the specimen arrived in; the closest thing on record to a specimen type. */
        String specimenType,
        /** ROUTINE / URGENT / STAT. On the report because it changes how a result is read. */
        String priority,
        OffsetDateTime collectedAt,
        OffsetDateTime authorizedAt,
        String authorizedBy,
        String clinicalNote,
        List<ResultRow> results
) {
    public LabReportData {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public record ResultRow(
            String parameter,
            String value,
            String unit,
            String referenceRange,
            String flag
    ) {
        public boolean abnormal() {
            return flag != null && !flag.isBlank() && !"NORMAL".equalsIgnoreCase(flag);
        }
    }
}
