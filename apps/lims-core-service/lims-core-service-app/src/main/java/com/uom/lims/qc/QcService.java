package com.uom.lims.qc;

import com.uom.lims.api.dto.response.QcDashboardResponse;
import com.uom.lims.api.dto.response.QcRunItemResponse;
import com.uom.lims.security.SecurityUtils;
import com.uom.lims.instrument.InstrumentRepository;
import com.uom.lims.repository.TestParameterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Records internal-QC runs, evaluating each against the Westgard multirules, and
 * serves the QC dashboard from persisted data only.
 *
 * <p>Whether a recorded verdict permits a patient result to be released is a
 * separate question, answered by {@link QcGateService}.
 */
@Service
@RequiredArgsConstructor
public class QcService {

    // Zone-less on purpose: the laboratory's zone is bound at format time, so a
    // container running in UTC still prints the time the bench actually ran the QC.
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM, hh:mm a");
    private static final int SERIES_WINDOW = 30;

    private final QcResultRepository repository;
    private final InstrumentRepository instrumentRepository;
    private final TestParameterRepository testParameterRepository;
    private final com.uom.lims.audit.AuditService auditService;
    private final com.uom.lims.config.LabTimeZone labTimeZone;

    /**
     * @param instrument must be a code from the instrument registry — the release
     *                   gate joins on it, so a free-text name silently disconnects
     *                   the control from every result it should govern
     * @param loincCode  the coded analyte controlled, matching
     *                   {@code test_parameters.loinc_code}; {@code analyte} is the
     *                   human label and is not a join key
     */
    public record RecordQcRunRequest(String instrument, String analyte, String loincCode, String controlLevel,
                                     String controlLot, BigDecimal measuredValue, BigDecimal mean, BigDecimal sd) {
    }

    public record QcRunOutcome(String id, String status, List<String> violations) {
    }

    @Transactional
    public QcRunOutcome record(RecordQcRunRequest req) {
        if (req.measuredValue() == null || req.mean() == null
                || req.sd() == null || req.sd().signum() <= 0) {
            throw new com.uom.lims.exception.BusinessValidationException(
                    "measuredValue, mean and a positive sd are required");
        }
        // The gate's join key must be validated where the control is written. If a
        // free-text instrument name or a blank LOINC is accepted here, the control
        // is recorded, looks fine on the dashboard, and governs nothing — which is
        // exactly the failure this whole change exists to remove.
        if (instrumentRepository.findByCodeAndActiveTrue(req.instrument()).isEmpty()) {
            throw new com.uom.lims.exception.BusinessValidationException(
                    "Unknown or inactive instrument code '" + req.instrument()
                            + "'. Use a code from the instrument registry.");
        }
        if (req.loincCode() == null || req.loincCode().isBlank()) {
            throw new com.uom.lims.exception.BusinessValidationException(
                    "loincCode is required — it is how this control is matched to the results it governs.");
        }
        if (!testParameterRepository.existsByLoincCode(req.loincCode().trim())) {
            throw new com.uom.lims.exception.BusinessValidationException(
                    "No test parameter uses LOINC '" + req.loincCode() + "'.");
        }

        double value = req.measuredValue().doubleValue();
        double mean = req.mean().doubleValue();
        double sd = req.sd().doubleValue();

        // Oldest-first history for the same control series.
        List<QcResultEntity> recent = repository
                .findByInstrumentAndAnalyteAndControlLevelOrderByPerformedAtDesc(
                        req.instrument(), req.analyte(), req.controlLevel(), PageRequest.of(0, SERIES_WINDOW));
        List<Double> history = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            history.add(recent.get(i).getMeasuredValue().doubleValue());
        }

        WestgardEvaluator.Evaluation eval = WestgardEvaluator.evaluate(value, mean, sd, history);
        String status = eval.rejected() ? "FAIL" : (eval.violations().contains("1-2s") ? "WARN" : "PASS");

        QcResultEntity entity = new QcResultEntity();
        entity.setInstrument(req.instrument());
        entity.setAnalyte(req.analyte());
        entity.setLoincCode(req.loincCode().trim());
        entity.setControlLevel(req.controlLevel());
        entity.setControlLot(req.controlLot());
        entity.setMeasuredValue(req.measuredValue());
        entity.setMean(req.mean());
        entity.setSd(req.sd());
        entity.setZScore(BigDecimal.valueOf((value - mean) / sd).setScale(3, RoundingMode.HALF_UP));
        entity.setStatus(status);
        entity.setViolations(String.join(",", eval.violations()));
        entity.setPerformedBy(SecurityUtils.getCurrentUsername());
        entity.setBranchCode(SecurityUtils.getCurrentBranchId());
        repository.save(entity);

        String details = String.format("{\"analyte\":\"%s\", \"controlLevel\":\"%s\", \"status\":\"%s\"}", req.analyte(), req.controlLevel(), status);
        auditService.log("RECORD_QC_RESULT", "QC_RESULT", entity.getId(), null, details, getCurrentIp());

        return new QcRunOutcome(entity.getId().toString(), status, eval.violations());
    }

    private String getCurrentIp() {
        try {
            return com.uom.lims.security.ClientIpResolver.resolve(
                ((org.springframework.web.context.request.ServletRequestAttributes) 
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest());
        } catch (Exception e) {
            return "SYSTEM";
        }
    }

    @Transactional(readOnly = true)
    public QcDashboardResponse getDashboard() {
        List<QcResultEntity> recent = repository.findByOrderByPerformedAtDesc(PageRequest.of(0, 50));
        // No fallback to the seeded demo runs. It used to return six fabricated PASS
        // rows whenever real QC was empty, which is indefensible next to a gate that
        // holds patient results on QC state: the screen would show controls passing
        // while every result was held for NO_QC. An empty QC dashboard is the true
        // answer and the one that prompts somebody to run controls.
        List<QcRunItemResponse> runs = recent.stream().map(this::toRun).toList();
        int passed = (int) runs.stream().filter(r -> "PASS".equals(r.status())).count();
        int warnings = (int) runs.stream().filter(r -> "WARN".equals(r.status())).count();
        int failures = (int) runs.stream().filter(r -> "FAIL".equals(r.status())).count();
        return new QcDashboardResponse(runs.size(), passed, warnings, failures, runs);
    }

    private QcRunItemResponse toRun(QcResultEntity e) {
        String formattedTime = formatTimestamp(e.getPerformedAt());
        return new QcRunItemResponse(
                e.getId().toString(), e.getInstrument(), e.getAnalyte(), e.getControlLevel(),
                e.getMeasuredValue().toPlainString(), e.getMean().toPlainString(), e.getSd().toPlainString(),
                e.getStatus(), e.getPerformedBy(), formattedTime);
    }

    private String formatTimestamp(java.time.Instant performedAt) {
        if (performedAt == null) return "-";
        ZoneId zone = labTimeZone.zone();
        java.time.LocalDate today = labTimeZone.today();
        java.time.LocalDate runDate = performedAt.atZone(zone).toLocalDate();
        if (today.equals(runDate)) {
            return TIME.withZone(zone).format(performedAt);
        }
        return DATE_TIME.withZone(zone).format(performedAt);
    }
}
