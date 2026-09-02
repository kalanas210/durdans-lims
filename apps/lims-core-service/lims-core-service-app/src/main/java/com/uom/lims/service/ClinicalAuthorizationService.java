package com.uom.lims.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uom.lims.api.clinical.dto.request.ClinicalAuthRequest;
import com.uom.lims.api.clinical.dto.request.ReturnToMLTRequest;
import com.uom.lims.api.dispatch.dto.request.RegisterAuthorizedReportRequest;
import com.uom.lims.api.dispatch.enums.DeliveryMethod;
import com.uom.lims.api.enums.ResultFlag;
import com.uom.lims.api.verification.dto.response.TestResultDetailResponse;
import com.uom.lims.api.verification.dto.response.PreviousVisitSummaryResponse;
import com.uom.lims.api.verification.dto.response.TestResultSummaryResponse;
import com.uom.lims.api.verification.dto.response.VerificationHistoryItemResponse;
import com.uom.lims.audit.AuditLog;
import com.uom.lims.audit.AuditLogRepository;
import com.uom.lims.api.enums.SampleStatus;
import com.uom.lims.audit.AuditService;
import com.uom.lims.api.verification.enums.ResultStatus;
import com.uom.lims.exception.ResourceNotFoundException;
import com.uom.lims.dispatch.DispatchService;
import com.uom.lims.entity.SampleEntity;
import com.uom.lims.entity.TestCatalogEntity;
import com.uom.lims.entity.TestResultEntity;
import com.uom.lims.exception.InvalidRequestException;
import com.uom.lims.exception.InvalidStateTransitionException;
import com.uom.lims.mapper.TestResultMapper;
import com.uom.lims.metadata.BranchRepository;
import com.uom.lims.patient.PatientEntity;
import com.uom.lims.patient.PatientRepository;
import com.uom.lims.repository.SampleRepository;
import com.uom.lims.repository.TestCatalogRepository;
import com.uom.lims.repository.TestResultRepository;
import com.uom.lims.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ClinicalAuthorizationService {
    private static final String ACTION_CLINICAL_AUTHORIZED = "CLINICAL_AUTHORIZED";
    private static final String ACTION_RETURNED_FROM_CLINICAL = "VERIFICATION_RETURNED_FROM_CLINICAL";
    private static final String VERIFICATION_ENTITY_TYPE = "VERIFICATION";
    private static final List<String> CLINICAL_HISTORY_ACTIONS = List.of(
            ACTION_CLINICAL_AUTHORIZED,
            ACTION_RETURNED_FROM_CLINICAL
    );

    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final TestResultRepository testResultRepository;
    private final SampleRepository sampleRepository;
    private final TestCatalogRepository testCatalogRepository;
    private final TestResultMapper testResultMapper;
    private final PatientRepository patientRepository;
    private final BranchRepository branchRepository;
    private final DispatchService dispatchService;
    private final com.uom.lims.config.LabTimeZone labTimeZone;
    private final CaseContextResolver caseContextResolver;

    @Transactional(readOnly = true)
    public Page<TestResultSummaryResponse> getPendingResults(int page, int size) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("lastModifiedAt"), Sort.Order.desc("id")));
        Page<SampleEntity> samplesPage = sampleRepository.findByStatusInAndBranch(
                List.of(SampleStatus.VERIFIED),
                SecurityUtils.resolveBranchScope(),
                pageable);

        List<UUID> testIds = samplesPage.getContent().stream()
                .map(sample -> sample.getOrderItem().getTestId())
                .distinct()
                .toList();

        Map<UUID, String> testNamesById = testIds.isEmpty()
                ? Map.of()
                : testCatalogRepository.findAllByIdInAndActiveTrueAndDeletedFalse(testIds).stream()
                .collect(Collectors.toMap(TestCatalogEntity::getId, TestCatalogEntity::getTestName));

        Map<String, String> patientNamesById = new HashMap<>();
        samplesPage.getContent().stream()
                .map(sample -> sample.getOrderItem().getOrder().getPatientId())
                .filter(patientId -> patientId != null && !patientId.isBlank())
                .distinct()
                .forEach(patientId -> patientNamesById.put(patientId, safelyResolvePatientName(patientId)));

        return samplesPage.map(sample -> buildClinicalQueueSummary(sample, testNamesById, patientNamesById));
    }

    /** One pathologist worklist row per completed lab verification (specimen), not per parameter. */
    private TestResultSummaryResponse buildClinicalQueueSummary(
            SampleEntity sample,
            Map<UUID, String> testNamesById,
            Map<String, String> patientNamesById) {
        List<TestResultEntity> verifiedParams = testResultRepository.findBySampleId(sample.getId()).stream()
                .filter(tr -> !tr.isDeleted())
                .filter(tr -> !Boolean.TRUE.equals(tr.getDraft()))
                .filter(tr -> tr.getStatus() == ResultStatus.TECHNICALLY_VERIFIED)
                .toList();

        UUID testId = sample.getOrderItem().getTestId();
        String patientId = sample.getOrderItem().getOrder().getPatientId();
        String testName = testNamesById.getOrDefault(testId, "UNKNOWN_TEST");
        String patientName = patientNamesById.getOrDefault(patientId, "UNKNOWN_PATIENT");

        if (verifiedParams.isEmpty()) {
            TestResultEntity fallback = testResultRepository.findBySampleId(sample.getId()).stream()
                    .filter(tr -> !tr.isDeleted())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No test results for clinically pending sample: " + sample.getId()));
            TestResultSummaryResponse base =
                    testResultMapper.toSummaryResponse(fallback, testName, patientName, patientId);
            boolean criticalFinding = testResultRepository.findBySampleId(sample.getId()).stream()
                    .filter(tr -> !tr.isDeleted())
                    .filter(tr -> !Boolean.TRUE.equals(tr.getDraft()))
                    .anyMatch(tr -> tr.getFlag() == ResultFlag.CRITICAL_HIGH || tr.getFlag() == ResultFlag.CRITICAL_LOW);
            return TestResultSummaryResponse.builder()
                    .resultId(base.getResultId())
                    .status(base.getStatus())
                    .patientCode(base.getPatientCode())
                    .patientName(base.getPatientName())
                    .testType(base.getTestType())
                    .mltName(base.getMltName())
                    .qcStatus(base.getQcStatus())
                    .flag(base.getFlag())
                    .priorityLevel(sample.getPriority() == null ? null : sample.getPriority().name())
                    .hasCriticalFinding(criticalFinding)
                    .createdAt(base.getCreatedAt())
                    .updatedAt(sample.getLastModifiedAt() != null ? sample.getLastModifiedAt() : base.getUpdatedAt())
                    .technicianName(base.getTechnicianName())
                    .pathologistName(base.getPathologistName())
                    .returnReason(base.getReturnReason())
                    .build();
        }

        TestResultEntity primary = verifiedParams.stream()
                .min(Comparator
                        .comparing((TestResultEntity tr) -> tr.getParameter().getDisplayOrder(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(tr -> tr.getParameter().getName(), String.CASE_INSENSITIVE_ORDER))
                .orElse(verifiedParams.get(0));

        ResultFlag worstFlag = verifiedParams.stream()
                .map(TestResultEntity::getFlag)
                .filter(Objects::nonNull)
                .max(Comparator.comparingInt(this::clinicalFlagSeverity))
                .orElse(null);

        boolean hasCriticalFinding = verifiedParams.stream()
                .anyMatch(tr -> tr.getFlag() == ResultFlag.CRITICAL_HIGH || tr.getFlag() == ResultFlag.CRITICAL_LOW);

        TestResultSummaryResponse base =
                testResultMapper.toSummaryResponse(primary, testName, patientName, patientId);
        return TestResultSummaryResponse.builder()
                .resultId(base.getResultId())
                .status(ResultStatus.TECHNICALLY_VERIFIED.name())
                .patientCode(base.getPatientCode())
                .patientName(base.getPatientName())
                .testType(base.getTestType())
                .mltName(base.getMltName())
                .qcStatus(base.getQcStatus())
                .flag(worstFlag != null ? worstFlag.name() : base.getFlag())
                .priorityLevel(sample.getPriority() == null ? null : sample.getPriority().name())
                .hasCriticalFinding(hasCriticalFinding)
                .createdAt(base.getCreatedAt())
                .updatedAt(sample.getLastModifiedAt() != null ? sample.getLastModifiedAt() : base.getUpdatedAt())
                .technicianName(base.getTechnicianName())
                .pathologistName(base.getPathologistName())
                .returnReason(base.getReturnReason())
                .build();
    }

    private int clinicalFlagSeverity(ResultFlag flag) {
        return switch (flag) {
            case NORMAL -> 0;
            case LOW, HIGH -> 1;
            case CRITICAL_LOW, CRITICAL_HIGH -> 2;
        };
    }

    @Transactional(readOnly = true)
    public TestResultDetailResponse getResultDetails(UUID resultId) {
        TestResultEntity result = findResultById(resultId);
        return buildDetailResponse(result);
    }

    @Transactional(readOnly = true)
    public Page<VerificationHistoryItemResponse> getClinicalHistory(
            int page,
            int size,
            String actionType,
            String search,
            java.time.LocalDateTime fromTimestamp
    ) {
        List<String> actions = resolveHistoryActions(actionType, CLINICAL_HISTORY_ACTIONS);
        if (actions.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }

        Page<AuditLog> auditPage = auditLogRepository
                .findHistoryByEntityTypeAndActions(
                        VERIFICATION_ENTITY_TYPE,
                        actions,
                        CaseContextResolver.normalizeHistorySearch(search),
                        fromTimestamp,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp"))
                );

        Map<UUID, HistoryCaseRef> cases = resolveHistoryCases(auditPage.getContent());
        return auditPage.map(auditLog -> toHistoryItemResponse(auditLog, cases));
    }

    /** Patient identity and case number for one audited action, resolved for the history table. */
    private record HistoryCaseRef(String patientCode, String patientName, String resultNo) {
    }

    /**
     * Resolve the patient and case number behind each audit row for a whole page
     * at once.
     *
     * <p>The audit row's own patient_code column carries the specimen barcode on
     * these writes, so identity comes from the result the row points at:
     * result -> sample -> order -> patient. Batched to two queries per page rather
     * than two per row.
     */
    private Map<UUID, HistoryCaseRef> resolveHistoryCases(List<AuditLog> auditLogs) {
        List<UUID> resultIds = auditLogs.stream()
                .map(AuditLog::getEntityId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (resultIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> codeByResultId = new HashMap<>();
        Map<UUID, String> resultNoByResultId = new HashMap<>();
        for (TestResultEntity result : testResultRepository.findAllById(resultIds)) {
            String code = safelyResolvePatientId(result);
            if (code != null && !code.isBlank()) {
                codeByResultId.put(result.getId(), code.trim());
            }
            if (result.getSample() != null && result.getSample().getResultNo() != null) {
                resultNoByResultId.put(result.getId(), result.getSample().getResultNo());
            }
        }

        Map<String, String> nameByCode = codeByResultId.isEmpty()
                ? Map.of()
                : patientRepository
                .findByPatientCodeIn(new java.util.HashSet<>(codeByResultId.values()))
                .stream()
                .collect(Collectors.toMap(
                        PatientEntity::getPatientCode,
                        PatientEntity::getFullName,
                        (first, second) -> first));

        Map<UUID, HistoryCaseRef> resolved = new HashMap<>();
        for (UUID resultId : resultIds) {
            String code = codeByResultId.get(resultId);
            String resultNo = resultNoByResultId.get(resultId);
            if (code == null && resultNo == null) {
                continue;
            }
            resolved.put(resultId, new HistoryCaseRef(code, code == null ? null : nameByCode.get(code), resultNo));
        }
        return resolved;
    }

    @Transactional
    public TestResultDetailResponse authorizeResult(UUID resultId, ClinicalAuthRequest request) {
        TestResultEntity anchor = findResultById(resultId);

        if (!Boolean.TRUE.equals(request.getSignatureConfirmed())) {
            throw new InvalidRequestException("Pathologist signature confirmation is required before authorization.");
        }

        // The interpretation is what the referring clinician reads; a report released
        // without one is a number without a meaning. Mandatory here, not only in the UI.
        String clinicalNote = request.getClinicalNote() == null ? null : request.getClinicalNote().trim();
        if (clinicalNote == null || clinicalNote.isEmpty()) {
            throw new InvalidRequestException("A clinical interpretation is required before authorization.");
        }

        List<TestResultEntity> targets = testResultRepository.findBySampleId(anchor.getSample().getId()).stream()
                .filter(tr -> !tr.isDeleted())
                .filter(tr -> !Boolean.TRUE.equals(tr.getDraft()))
                .filter(tr -> tr.getStatus() == ResultStatus.TECHNICALLY_VERIFIED)
                .toList();

        if (targets.isEmpty()) {
            throw new InvalidStateTransitionException(
                    "No technically verified parameters to authorize for this sample.");
        }

        String username = SecurityUtils.getCurrentUsername();
        String actorName = currentActorName();
        Instant now = Instant.now();
        SampleEntity sample = anchor.getSample();
        sample.setStatus(SampleStatus.AUTHORIZED);
        sampleRepository.save(sample);

        // The signature is printed on the released report, so it carries the same
        // display name as the authorized-by field rather than the raw login id.
        String signature = String.format("Electronically authorized by %s on %s", actorName, now);
        for (TestResultEntity result : targets) {
            result.setStatus(ResultStatus.CLINICALLY_AUTHORIZED);
            result.setClinicalNote(clinicalNote);
            result.setClinicallyAuthorizedBy(actorName);
            result.setClinicallyAuthorizedAt(now);
            result.setClinicalSignature(signature);
            result.setLastModifiedBy(username);
            result.setLastModifiedAt(now);
            testResultRepository.save(result);
        }

        registerAuthorizedReportForDispatch(anchor);
        logClinicalAuthorized(anchor, clinicalNote);
        return buildDetailResponse(anchor);
    }

    @Transactional
    public TestResultDetailResponse returnToMlt(UUID resultId, ReturnToMLTRequest request) {
        TestResultEntity anchor = findResultById(resultId);

        List<TestResultEntity> targets = testResultRepository.findBySampleId(anchor.getSample().getId()).stream()
                .filter(tr -> !tr.isDeleted())
                .filter(tr -> !Boolean.TRUE.equals(tr.getDraft()))
                .filter(tr -> tr.getStatus() == ResultStatus.TECHNICALLY_VERIFIED)
                .toList();

        if (targets.isEmpty()) {
            throw new InvalidStateTransitionException(
                    "No technically verified parameters to return to the lab for this sample.");
        }

        String username = SecurityUtils.getCurrentUsername();
        String actorName = currentActorName();
        Instant now = Instant.now();
        SampleEntity sample = anchor.getSample();
        sample.setStatus(SampleStatus.SENT_FOR_VERIFICATION);
        sampleRepository.save(sample);

        for (TestResultEntity result : targets) {
            result.setStatus(ResultStatus.RETURNED_FOR_RECHECK);
            result.setReturnReason(request.getReturnReason());
            result.setReturnedBy(actorName);
            result.setReturnedAt(now);
            result.setLastModifiedBy(username);
            result.setLastModifiedAt(now);
            testResultRepository.save(result);
        }

        logReturnedFromClinical(anchor, request.getReturnReason());
        return buildDetailResponse(anchor);
    }

    private TestResultDetailResponse buildDetailResponse(TestResultEntity result) {
        List<TestResultEntity> caseResults = testResultRepository.findBySampleId(result.getSample().getId());
        String patientId = safelyResolvePatientId(result);
        UUID testId = safelyResolveTestId(result);

        String testType = testId == null
                ? null
                : testCatalogRepository.findById(testId)
                .filter(TestCatalogEntity::isActive)
                .filter(catalog -> !catalog.isDeleted())
                .map(TestCatalogEntity::getTestName)
                .orElse(null);

        PatientEntity patient = resolvePatientEntity(patientId).orElse(null);
        String patientName = patient != null ? patient.getFullName() : null;
        Integer patientAge = patient == null ? null : calculatePatientAge(patient);
        String patientGender = patient == null || patient.getGender() == null ? null : patient.getGender().name();

        // One query serves both the "previous visits" panel and the per-parameter
        // delta column, so the two can never disagree about what "previous" means.
        List<TestResultEntity> priorResults = caseContextResolver.priorResults(patientId, testId, result.getSample());
        List<PreviousVisitSummaryResponse> previousVisits = toPreviousVisits(priorResults);
        Map<UUID, TestResultEntity> priorByParameter = caseContextResolver.latestReleasedByParameter(priorResults);

        return testResultMapper.toDetailResponse(
                result,
                caseResults,
                patientId,
                patientName,
                testType,
                patientAge,
                patientGender,
                previousVisits,
                priorByParameter,
                caseContextResolver.receivedAt(result.getSample().getId())
        );
    }

    /**
     * Single loader for every result this service touches. The tenant guard lives
     * here so clinical authorization and return-for-recheck cannot be performed
     * on another branch's result by supplying its id.
     *
     * <p>A result has no branch of its own; it inherits the branch of the order
     * that requested the specimen.
     */
    private TestResultEntity findResultById(UUID id) {
        TestResultEntity result = testResultRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Test result not found: " + id));
        SecurityUtils.assertCanAccessBranch(branchOf(result), "Test result", id);
        return result;
    }

    private static String branchOf(TestResultEntity result) {
        SampleEntity sample = result.getSample();
        if (sample == null || sample.getOrderItem() == null || sample.getOrderItem().getOrder() == null) {
            return null; // unreachable for anyone but SUPER_ADMIN — fail closed
        }
        return sample.getOrderItem().getOrder().getBranchCode();
    }

    private List<String> resolveHistoryActions(String actionType, List<String> allowedActions) {
        if (actionType == null || actionType.isBlank()) {
            return allowedActions;
        }

        return allowedActions.contains(actionType) ? List.of(actionType) : List.of();
    }

    private String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }

        String trimmed = search.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void registerAuthorizedReportForDispatch(TestResultEntity result) {
        String patientId = safelyResolvePatientId(result);
        PatientEntity patient = resolvePatientEntity(patientId).orElse(null);
        String branchCode = resolveDispatchBranchCode(patient);
        if (branchCode == null || branchCode.isBlank()) {
            throw new InvalidRequestException("Could not resolve a branch for dispatch registration.");
        }

        UUID testId = safelyResolveTestId(result);
        String testType = testId == null
                ? "Unknown Test Group"
                : testCatalogRepository.findById(testId)
                .filter(TestCatalogEntity::isActive)
                .filter(catalog -> !catalog.isDeleted())
                .map(TestCatalogEntity::getTestName)
                .orElse("Unknown Test Group");

        RegisterAuthorizedReportRequest request = RegisterAuthorizedReportRequest.builder()
                .reportReference(result.getId().toString())
                .branchCode(branchCode)
                .patientCode(patientId)
                .patientDisplayName(patient != null && patient.getFullName() != null
                        ? patient.getFullName()
                        : "Unknown patient")
                .testPanelLabel(testType)
                .authorizedAt(result.getClinicallyAuthorizedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(result.getClinicallyAuthorizedAt(), labTimeZone.zone()))
                .preferredDeliveryMethods(List.of(
                        DeliveryMethod.SMS,
                        DeliveryMethod.EMAIL))
                .build();

        dispatchService.registerAuthorizedReportSystem(request, "clinical-authorization");
    }

    private String resolveDispatchBranchCode(PatientEntity patient) {
        String securityBranch = SecurityUtils.getCurrentBranchId();
        if (securityBranch != null && !securityBranch.isBlank()) {
            return securityBranch;
        }
        if (patient != null && patient.getBranchCode() != null && !patient.getBranchCode().isBlank()) {
            return patient.getBranchCode();
        }
        return branchRepository.findByCode("BR001")
                .map(branch -> branch.getCode())
                .orElseGet(() -> branchRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                        .findFirst()
                        .map(branch -> branch.getCode())
                        .orElse(null));
    }

    private void logReturnedFromClinical(TestResultEntity result, String notes) {
        logClinicalEvent(result, ACTION_RETURNED_FROM_CLINICAL, notes);
    }

    private void logClinicalAuthorized(TestResultEntity result, String notes) {
        logClinicalEvent(result, ACTION_CLINICAL_AUTHORIZED, notes);
    }

    private void logClinicalEvent(TestResultEntity result, String action, String notes) {
        TestCatalogEntity catalog = testCatalogRepository.findById(result.getSample().getOrderItem().getTestId())
                .orElse(null);

        // Everything the history screen shows and searches on is written into the
        // row itself, so the audit trail stays readable even if the result it points
        // at is later purged, and so the all-fields search has something to match.
        Map<String, String> details = new HashMap<>();
        details.put("testName", catalog == null ? "Unknown Test Group" : catalog.getTestName());
        details.put("actionSummary", getActionSummary(action));
        if (result.getSample().getPriority() != null) {
            details.put("specimenPriority", result.getSample().getPriority().name());
        }
        if (result.getSample().getResultNo() != null) {
            details.put("resultNo", result.getSample().getResultNo());
        }
        String patientCode = safelyResolvePatientId(result);
        if (patientCode != null && !patientCode.isBlank()) {
            details.put("patientCode", patientCode);
            String patientName = safelyResolvePatientName(patientCode);
            if (patientName != null && !patientName.isBlank()) {
                details.put("patientName", patientName);
            }
        }
        String performedById = SecurityUtils.getCurrentUserId();
        if (performedById != null && !performedById.isBlank()) {
            details.put("performedById", performedById);
        }
        if (notes != null && !notes.isBlank()) {
            details.put("notes", notes);
        }

        try {
            auditService.log(
                    action,
                    VERIFICATION_ENTITY_TYPE,
                    result.getId(),
                    result.getSample().getBarcode(),
                    objectMapper.writeValueAsString(details),
                    null
            );
        } catch (Exception exception) {
            throw new RuntimeException("Failed to log clinical history event", exception);
        }
    }

    private VerificationHistoryItemResponse toHistoryItemResponse(
            AuditLog auditLog,
            Map<UUID, HistoryCaseRef> cases) {
        Map<String, String> details = parseDetails(auditLog.getDetails());
        // AuditService writes LocalDateTime.now(UTC); read it back the same way so a
        // host in another zone does not shift every history time by its offset.
        Instant actionAt = auditLog.getTimestamp() == null
                ? null
                : auditLog.getTimestamp().atOffset(ZoneOffset.UTC).toInstant();
        HistoryCaseRef caseRef = auditLog.getEntityId() == null
                ? null
                : cases.get(auditLog.getEntityId());

        return VerificationHistoryItemResponse.builder()
                .resultId(auditLog.getEntityId() == null ? "" : auditLog.getEntityId().toString())
                .resultNo(caseRef != null && caseRef.resultNo() != null
                        ? caseRef.resultNo()
                        : details.get("resultNo"))
                .actionType(auditLog.getAction())
                .patientCode(caseRef == null ? details.get("patientCode") : caseRef.patientCode())
                .patientName(caseRef == null ? details.get("patientName") : caseRef.patientName())
                .testName(details.getOrDefault("testName", "Unknown Test Group"))
                .specimenPriority(details.get("specimenPriority"))
                .actionSummary(getActionSummary(auditLog.getAction()))
                .performedBy(auditLog.getPerformedBy())
                .actionAt(actionAt)
                .notes(details.get("notes"))
                .updatedAt(actionAt)
                .build();
    }

    private String getActionSummary(String action) {
        if (ACTION_CLINICAL_AUTHORIZED.equals(action)) {
            return "Authorized by Pathologist";
        }
        if (ACTION_RETURNED_FROM_CLINICAL.equals(action)) {
            return "Returned to Supervisor";
        }
        return "Workflow Updated";
    }

    private Map<String, String> parseDetails(String rawDetails) {
        if (rawDetails == null || rawDetails.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(
                    rawDetails,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class)
            );
        } catch (Exception exception) {
            return Map.of("notes", rawDetails);
        }
    }

    private UUID safelyResolveTestId(TestResultEntity result) {
        try {
            return result.getSample().getOrderItem().getTestId();
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Human-facing actor for the fields a report shows: the token's display name
     * when it carries one, else the login id. A verified-by line reading
     * "Dr N. Perera" is what a clinician can act on; "nperera" is not.
     */
    private static String currentActorName() {
        String displayName = SecurityUtils.getCurrentDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return SecurityUtils.getCurrentUsername();
    }

    private String safelyResolvePatientId(TestResultEntity result) {
        try {
            return result.getSample().getOrderItem().getOrder().getPatientId();
        } catch (Exception exception) {
            return null;
        }
    }

    private String safelyResolvePatientName(String patientId) {
        try {
            return resolvePatientName(patientId);
        } catch (Exception exception) {
            return null;
        }
    }

    private String resolvePatientName(String patientId) {
        return resolvePatientEntity(patientId)
                .map(PatientEntity::getFullName)
                .orElse(null);
    }

    private java.util.Optional<PatientEntity> resolvePatientEntity(String patientId) {
        if (patientId == null || patientId.isBlank()) {
            return java.util.Optional.empty();
        }

        String normalizedPatientId = patientId.trim();

        return patientRepository.findByPatientCode(normalizedPatientId)
                .or(() -> resolvePatientByUuid(normalizedPatientId));
    }

    private java.util.Optional<PatientEntity> resolvePatientByUuid(String patientId) {
        try {
            return patientRepository.findById(UUID.fromString(patientId));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private Integer calculatePatientAge(PatientEntity patient) {
        if (patient.getDob() == null) {
            return null;
        }

        return Period.between(patient.getDob(), LocalDate.now()).getYears();
    }

    /** Prior results (newest visit first) grouped into the last five visits. */
    private List<PreviousVisitSummaryResponse> toPreviousVisits(List<TestResultEntity> priorResults) {
        Map<UUID, List<TestResultEntity>> resultsBySample = priorResults.stream()
                .collect(Collectors.groupingBy(
                        result -> result.getSample().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return resultsBySample.values().stream()
                .sorted(Comparator.comparing(this::resolveVisitedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toPreviousVisitSummary)
                .limit(5)
                .toList();
    }

    private PreviousVisitSummaryResponse toPreviousVisitSummary(List<TestResultEntity> sampleResults) {
        TestResultEntity primaryResult = sampleResults.get(0);
        Instant visitedAt = resolveVisitedAt(sampleResults);
        int abnormalCount = (int) sampleResults.stream()
                .filter(result -> result.getFlag() != null && result.getFlag() != ResultFlag.NORMAL)
                .count();
        int criticalCount = (int) sampleResults.stream()
                .filter(result -> result.getFlag() == ResultFlag.CRITICAL_HIGH || result.getFlag() == ResultFlag.CRITICAL_LOW)
                .count();

        return PreviousVisitSummaryResponse.builder()
                .resultId(primaryResult.getId().toString())
                .resultNo(primaryResult.getSample().getResultNo())
                .sampleId(primaryResult.getSample().getId().toString())
                .status(primaryResult.getStatus() == null ? null : primaryResult.getStatus().name())
                .priorityLevel(primaryResult.getSample().getPriority() == null
                        ? null
                        : primaryResult.getSample().getPriority().name())
                .visitedAt(visitedAt)
                .parameterCount(sampleResults.size())
                .abnormalCount(abnormalCount)
                .criticalCount(criticalCount)
                .build();
    }

    private Instant resolveVisitedAt(List<TestResultEntity> sampleResults) {
        return CaseContextResolver.visitedAt(sampleResults.get(0).getSample());
    }
}
