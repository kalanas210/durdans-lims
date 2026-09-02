package com.uom.lims.dispatch;

import com.uom.lims.entity.OrderEntity;
import com.uom.lims.entity.OrderItemEntity;
import com.uom.lims.entity.SampleEntity;
import com.uom.lims.entity.TestResultEntity;
import com.uom.lims.patient.PatientEntity;
import com.uom.lims.patient.PatientRepository;
import com.uom.lims.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LabReportDataService {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Colombo");

    private final TestResultRepository testResultRepository;
    private final PatientRepository patientRepository;

    @Transactional(readOnly = true)
    public LabReportData resolve(ReportDispatchItemEntity item) {
        Optional<TestResultEntity> anchor = findAnchor(item.getReportReference());
        if (anchor.isEmpty()) {
            return fallback(item);
        }

        TestResultEntity anchorResult = anchor.get();
        SampleEntity sample = anchorResult.getSample();
        OrderItemEntity orderItem = sample == null ? null : sample.getOrderItem();
        OrderEntity order = orderItem == null ? null : orderItem.getOrder();
        String patientCode = order == null ? item.getPatientCode() : text(order.getPatientId());
        PatientEntity patient = patientCode == null
                ? null
                : patientRepository.findByPatientCode(patientCode).orElse(null);

        List<TestResultEntity> sourceResults = sample == null
                ? List.of(anchorResult)
                : testResultRepository.findBySampleId(sample.getId()).stream()
                .filter(result -> !result.isDeleted())
                .filter(result -> !Boolean.TRUE.equals(result.getDraft()))
                .sorted(Comparator
                        .comparing((TestResultEntity result) -> result.getParameter().getDisplayOrder(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(result -> result.getParameter().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        OffsetDateTime authorizedAt = anchorResult.getClinicallyAuthorizedAt() == null
                ? item.getAuthorizedAt().atZone(DISPLAY_ZONE).toOffsetDateTime()
                : anchorResult.getClinicallyAuthorizedAt().atZone(DISPLAY_ZONE).toOffsetDateTime();

        return new LabReportData(
                item.getReportReference(),
                item.getReportNo(),
                item.getBranchCode(),
                patientCode,
                patient == null ? item.getPatientDisplayName() : patient.getFullName(),
                patient == null ? null : patient.getDob(),
                patient == null || patient.getGender() == null ? null : patient.getGender().name(),
                order == null ? null : text(order.getReferringDoctor()),
                order == null ? null : text(order.getReferringDepartment()),
                item.getTestPanelLabel(),
                sample == null ? null : sample.getBarcode(),
                sample == null ? null : sample.getResultNo(),
                sample == null || sample.getTubeType() == null ? null : sample.getTubeType().name(),
                sample == null || sample.getPriority() == null ? null : sample.getPriority().name(),
                sample == null || sample.getCollectedAt() == null
                        ? null : sample.getCollectedAt().atZone(DISPLAY_ZONE).toOffsetDateTime(),
                authorizedAt,
                firstNonBlank(sourceResults.stream().map(TestResultEntity::getClinicallyAuthorizedBy).toList()),
                firstNonBlank(sourceResults.stream().map(TestResultEntity::getClinicalNote).toList()),
                sourceResults.stream().map(this::toRow).toList());
    }

    private Optional<TestResultEntity> findAnchor(String reportReference) {
        try {
            return testResultRepository.findById(UUID.fromString(reportReference));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Optional.empty();
        }
    }

    private LabReportData.ResultRow toRow(TestResultEntity result) {
        String flag = result.getFlag() == null ? "NORMAL" : result.getFlag().name();
        return new LabReportData.ResultRow(
                result.getParameter().getName(),
                result.getResultValue(),
                result.getParameter().getUnit(),
                referenceRange(result.getParameter().getRefLow(), result.getParameter().getRefHigh()),
                flag);
    }

    private LabReportData fallback(ReportDispatchItemEntity item) {
        return new LabReportData(
                item.getReportReference(), item.getReportNo(), item.getBranchCode(), item.getPatientCode(),
                item.getPatientDisplayName(), null, null, null, null,
                item.getTestPanelLabel(), null, null, null, null, null,
                item.getAuthorizedAt().atZone(DISPLAY_ZONE).toOffsetDateTime(),
                null, null, List.of());
    }

    private static String referenceRange(BigDecimal low, BigDecimal high) {
        if (low == null && high == null) return null;
        if (low == null) return "<= " + decimal(high);
        if (high == null) return ">= " + decimal(low);
        return decimal(low) + " - " + decimal(high);
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String firstNonBlank(List<String> values) {
        return values.stream().map(LabReportDataService::text).filter(value -> value != null).findFirst().orElse(null);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
