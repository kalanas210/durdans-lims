package com.uom.lims.dispatch;

import com.uom.lims.api.dispatch.enums.DispatchItemStatus;
import com.uom.lims.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "report_dispatch_item")
public class ReportDispatchItemEntity extends BaseEntity {

    /** Anchor TestResult id — the lookup key and the dispatch API's path segment. */
    @Column(name = "report_reference", nullable = false, length = 100)
    private String reportReference;

    /**
     * {@code REP2026-00042} — what a person reads, prints and quotes. Issued once
     * by {@link ReportNumberService}; null only for rows the backfill has not run
     * against yet.
     */
    @Column(name = "report_no", length = 32)
    private String reportNo;

    @Column(name = "branch_code", nullable = false, length = 100)
    private String branchCode;

    @Column(name = "patient_code", length = 50)
    private String patientCode;

    @Column(name = "patient_display_name", nullable = false)
    private String patientDisplayName;

    @Column(name = "test_panel_label", nullable = false, length = 500)
    private String testPanelLabel;

    @Column(name = "artifact_uri", length = 2048)
    private String artifactUri;

    @Column(name = "authorized_at", nullable = false)
    private LocalDateTime authorizedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_status", nullable = false, length = 30)
    private DispatchItemStatus overallStatus;

    @Column(name = "preferred_methods", columnDefinition = "TEXT")
    private String preferredMethodsJson;

    @OneToMany(mappedBy = "dispatchItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReportDeliveryAttemptEntity> attempts = new ArrayList<>();
}
