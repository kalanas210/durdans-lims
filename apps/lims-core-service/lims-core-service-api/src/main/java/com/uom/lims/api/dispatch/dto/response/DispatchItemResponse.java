package com.uom.lims.api.dispatch.dto.response;

import com.uom.lims.api.dispatch.enums.DeliveryMethod;
import com.uom.lims.api.dispatch.enums.DispatchItemStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Authorized report in the dispatch queue with delivery attempts")
public class DispatchItemResponse {

    private UUID id;

    @Schema(description = "Anchor result id — the key this report is addressed by",
            example = "6cdc83c3-7a08-4f4a-87da-ad4fa149e1ad")
    private String reportReference;

    @Schema(description = "Human-readable report number, printed on the PDF and quoted by patients",
            example = "REP2026-00042")
    private String reportNo;

    private String branchCode;
    private String patientCode;
    private String patientDisplayName;
    private Integer patientAge;
    private String patientGender;
    private LocalDate patientDob;
    private String referringDoctor;
    private String ward;
    private String testPanelLabel;
    private String sampleId;
    private OffsetDateTime sampleCollectedAt;
    private OffsetDateTime reportGeneratedAt;
    private String authorizedBy;
    private String clinicalNote;
    private List<DispatchReportResultResponse> results;
    private String artifactUri;
    private OffsetDateTime authorizedAt;
    private DispatchItemStatus overallStatus;
    private List<DeliveryMethod> preferredDeliveryMethods;
    private List<DeliveryAttemptResponse> attempts;
}
