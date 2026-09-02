package com.uom.lims.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uom.lims.api.common.PageResponse;
import com.uom.lims.api.dispatch.dto.request.DispatchReportRequest;
import com.uom.lims.api.dispatch.dto.request.RegisterAuthorizedReportRequest;
import com.uom.lims.api.dispatch.dto.response.DeliveryAttemptResponse;
import com.uom.lims.api.dispatch.dto.response.DeliveryRecordResponse;
import com.uom.lims.api.dispatch.dto.response.DispatchDashboardItemResponse;
import com.uom.lims.api.dispatch.dto.response.DispatchItemResponse;
import com.uom.lims.api.dispatch.dto.response.DispatchReportResultResponse;
import com.uom.lims.api.dispatch.dto.response.FailedDeliveryResponse;
import com.uom.lims.api.dispatch.enums.DeliveryAttemptStatus;
import com.uom.lims.api.dispatch.enums.DeliveryMethod;
import com.uom.lims.api.dispatch.enums.DispatchItemStatus;
import com.uom.lims.api.enums.OrderStatus;
import com.uom.lims.api.enums.SampleStatus;
import com.uom.lims.audit.AuditService;
import com.uom.lims.entity.OrderEntity;
import com.uom.lims.entity.OrderItemEntity;
import com.uom.lims.entity.SampleEntity;
import com.uom.lims.api.verification.enums.ResultStatus;
import com.uom.lims.entity.TestResultEntity;
import com.uom.lims.event.ReportDispatchDomainEvent;
import com.uom.lims.exception.InvalidRequestException;
import com.uom.lims.exception.ResourceNotFoundException;
import com.uom.lims.patient.PatientEntity;
import com.uom.lims.patient.PatientRepository;
import com.uom.lims.repository.OrderItemRepository;
import com.uom.lims.repository.OrderRepository;
import com.uom.lims.repository.SampleRepository;
import com.uom.lims.repository.TestResultRepository;
import com.uom.lims.security.SecurityUtils;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DispatchService {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Colombo");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.UK);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.UK);
    private static final DateTimeFormatter RECORD_TS = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.UK);
    private static final List<DeliveryMethod> DEFAULT_DELIVERY_METHODS = List.of(
            DeliveryMethod.SMS,
            DeliveryMethod.EMAIL);

    private final ReportDispatchItemRepository itemRepository;
    private final ReportNumberService reportNumberService;
    private final ReportDeliveryAttemptRepository attemptRepository;
    private final ReportDispatchChannelService channelService;
    private final AuditService auditService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;
    private final TestResultRepository testResultRepository;
    private final SampleRepository sampleRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final PatientRepository patientRepository;

    // Self-proxy so the phase-1/phase-3 @Transactional helpers are honoured when
    // called from the non-transactional dispatch/retry methods (which perform the
    // channel send in between, OUTSIDE any transaction).
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DispatchService self;

    /**
     * Phase-1 result: the (now detached) dispatch item plus the attempts to send.
     */
    private record PreparedDispatch(ReportDispatchItemEntity item,
            java.util.List<ReportDeliveryAttemptEntity> attempts) {
    }

    @Transactional
    public DispatchItemResponse registerAuthorizedReport(RegisterAuthorizedReportRequest request, String ipAddress) {
        return registerAuthorizedReportInternal(request, ipAddress, true);
    }

    @Transactional
    public DispatchItemResponse registerAuthorizedReportSystem(RegisterAuthorizedReportRequest request,
            String ipAddress) {
        return registerAuthorizedReportInternal(request, ipAddress, false);
    }

    private DispatchItemResponse registerAuthorizedReportInternal(
            RegisterAuthorizedReportRequest request,
            String ipAddress,
            boolean validateBranchContext) {
        String branch = request.getBranchCode().trim().toUpperCase();
        if (validateBranchContext) {
            assertRegisterBranchAllowed(branch);
            // Caller-driven path only. The event-driven path
            // (registerAuthorizedReportSystem) fires from the authorization
            // transaction itself, so its result is authorized by construction.
            assertResultClinicallyAuthorized(request.getReportReference().trim(), branch);
        }

        LocalDateTime authorizedAt = request.getAuthorizedAt() != null
                ? request.getAuthorizedAt().atZoneSameInstant(DISPLAY_ZONE).toLocalDateTime()
                : LocalDateTime.now();

        Optional<ReportDispatchItemEntity> existing = itemRepository.findByReportReferenceAndBranchCode(
                request.getReportReference().trim(), branch);

        ReportDispatchItemEntity entity = existing.orElseGet(ReportDispatchItemEntity::new);
        entity.setReportReference(request.getReportReference().trim());
        entity.setBranchCode(branch);
        entity.setPatientCode(trimToNull(request.getPatientCode()));
        entity.setPatientDisplayName(request.getPatientDisplayName().trim());
        entity.setTestPanelLabel(request.getTestPanelLabel().trim());
        entity.setArtifactUri(trimToNull(request.getArtifactUri()));
        entity.setAuthorizedAt(authorizedAt);
        if (existing.isEmpty()) {
            entity.setOverallStatus(DispatchItemStatus.PENDING);
        }
        // Issued once and never reissued: re-authorizing or re-dispatching keeps the
        // number already printed on the copy the patient is holding.
        reportNumberService.ensureReportNo(entity);
        List<DeliveryMethod> preferredMethods = request.getPreferredDeliveryMethods() != null
                && !request.getPreferredDeliveryMethods().isEmpty()
                        ? request.getPreferredDeliveryMethods()
                        : DEFAULT_DELIVERY_METHODS;
        try {
            entity.setPreferredMethodsJson(objectMapper.writeValueAsString(preferredMethods));
        } catch (Exception e) {
            throw new InvalidRequestException("Could not serialize preferred delivery methods");
        }

        ReportDispatchItemEntity saved = itemRepository.save(entity);
        auditService.log(
                existing.isEmpty() ? "REGISTER_DISPATCH_ITEM" : "UPSERT_DISPATCH_ITEM",
                "REPORT_DISPATCH",
                saved.getId(),
                saved.getReportReference(),
                "{\"branch\":\"" + saved.getBranchCode() + "\"}",
                ipAddress);

        applicationEventPublisher.publishEvent(new ReportDispatchDomainEvent(
                existing.isEmpty() ? "DISPATCH_ITEM_REGISTERED" : "DISPATCH_ITEM_UPDATED",
                saved.getReportReference(),
                saved.getBranchCode(),
                saved.getOverallStatus().name(),
                LocalDateTime.now()));

        return mapToDetailResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DispatchDashboardItemResponse> listDispatchReports(
            DispatchItemStatus status,
            String branchCodeParam,
            String keyword,
            int page,
            int size,
            String sort) {

        Optional<String> branchFilter = resolveBranchFilterForQuery(branchCodeParam);
        Pageable pageable = buildPageable(page, size, sort, "authorizedAt");

        Specification<ReportDispatchItemEntity> spec = dispatchListSpec(branchFilter, Optional.ofNullable(status),
                Optional.ofNullable(keyword));
        Page<ReportDispatchItemEntity> result = itemRepository.findAll(spec, pageable);

        return new PageResponse<>(
                result.getContent().stream().map(this::toDashboardItem).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isLast());
    }

    @Transactional(readOnly = true)
    public DispatchItemResponse getDispatchReport(String reportReference, String branchCodeParam) {
        ReportDispatchItemEntity item = loadItemForCurrentUser(reportReference.trim(), branchCodeParam);
        return mapToDetailResponse(item);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryRecordResponse> listDeliveryRecords(
            DispatchItemStatus status,
            String branchCodeParam,
            String keyword,
            int page,
            int size,
            String sort) {

        Optional<String> branchFilter = resolveBranchFilterForQuery(branchCodeParam);
        Pageable pageable = buildPageable(page, size, sort, "authorizedAt");

        Specification<ReportDispatchItemEntity> spec = deliveryRecordsSpec(branchFilter, Optional.ofNullable(status),
                Optional.ofNullable(keyword));
        Page<ReportDispatchItemEntity> result = itemRepository.findAll(spec, pageable);

        return new PageResponse<>(
                result.getContent().stream().map(this::toDeliveryRecord).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isLast());
    }

    @Transactional(readOnly = true)
    public List<FailedDeliveryResponse> listFailedDeliveries(String branchCodeParam, int limit) {
        Optional<String> branchFilter = resolveBranchFilterForQuery(branchCodeParam);
        String branch = branchFilter.orElse(null);
        List<ReportDeliveryAttemptEntity> rows = attemptRepository.findRecentByStatusAndBranch(
                DeliveryAttemptStatus.FAILED, branch);
        return rows.stream().limit(Math.max(1, Math.min(limit, 200))).map(this::toFailedDelivery).toList();
    }

    public DispatchItemResponse dispatchReport(String reportReference, String branchCodeParam,
            DispatchReportRequest request,
            String ipAddress) {
        List<DeliveryMethod> methods = request.getMethods().stream().distinct().toList();
        if (methods.isEmpty()) {
            throw new InvalidRequestException("At least one delivery method is required");
        }

        // Phase 1 (txn): persist the attempts as PENDING and commit.
        PreparedDispatch prepared = self.persistPendingAttempts(reportReference.trim(), branchCodeParam, methods);

        // Phase 2 (NO txn): perform the blocking channel sends, mutating the
        // detached attempt objects in memory — no DB connection / lock held.
        for (ReportDeliveryAttemptEntity attempt : prepared.attempts()) {
            channelService.executeChannel(prepared.item(), attempt, request);
        }

        // Phase 3 (txn): merge the result and finalize.
        return self.finalizeDispatch(prepared.item(), prepared.attempts(), "DISPATCH_REPORT", "DISPATCH_EXECUTED",
                "{\"methods\":\"" + methods + "\"}", ipAddress);
    }

    @Transactional
    public PreparedDispatch persistPendingAttempts(String reportReference, String branchCodeParam,
            List<DeliveryMethod> methods) {
        ReportDispatchItemEntity item = loadItemForCurrentUser(reportReference, branchCodeParam);
        List<ReportDeliveryAttemptEntity> created = new ArrayList<>();
        for (DeliveryMethod method : methods) {
            ReportDeliveryAttemptEntity attempt = new ReportDeliveryAttemptEntity();
            attempt.setDispatchItem(item);
            attempt.setMethod(method);
            attempt.setStatus(DeliveryAttemptStatus.PENDING);
            attempt.setRetryCount(0);
            item.getAttempts().add(attempt);
            created.add(attempt);
        }
        try {
            item.setPreferredMethodsJson(objectMapper.writeValueAsString(methods));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not persist selected delivery methods", ex);
        }
        itemRepository.save(item); // assigns ids to the new attempts (cascade)
        return new PreparedDispatch(item, created);
    }

    public DispatchItemResponse retryAttempt(UUID attemptId, String ipAddress) {
        // Phase 1 (txn): validate + mark PENDING.
        PreparedDispatch prepared = self.prepareRetry(attemptId);
        ReportDeliveryAttemptEntity attempt = prepared.attempts().get(0);

        // Phase 2 (NO txn): send.
        DispatchReportRequest minimal = DispatchReportRequest.builder()
                .methods(List.of(attempt.getMethod()))
                .postalAddress(attempt.getRecipientContact())
                .trackingNumber(attempt.getTrackingNumber())
                .build();
        channelService.executeChannel(prepared.item(), attempt, minimal);

        // Phase 3 (txn): merge + finalize.
        return self.finalizeDispatch(prepared.item(), prepared.attempts(), "DISPATCH_RETRY", "DISPATCH_RETRY",
                "{\"attemptId\":\"" + attemptId + "\"}", ipAddress);
    }

    @Transactional
    public PreparedDispatch prepareRetry(UUID attemptId) {
        ReportDeliveryAttemptEntity attempt = attemptRepository.findByIdWithItem(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery attempt not found"));
        ReportDispatchItemEntity item = attempt.getDispatchItem();
        assertBranchAccess(item.getBranchCode());
        if (attempt.getStatus() != DeliveryAttemptStatus.FAILED) {
            throw new InvalidRequestException("Only failed attempts can be retried");
        }
        attempt.setRetryCount(attempt.getRetryCount() + 1);
        attempt.setStatus(DeliveryAttemptStatus.PENDING);
        attempt.setFailureReason(null);
        attempt.setDeliveredAt(null);
        item.getAttempts().size(); // force-initialise for the phase-3 aggregate
        itemRepository.save(item);
        return new PreparedDispatch(item, List.of(attempt));
    }

    /**
     * Phase 3: merge the detached item (with the mutated attempt statuses),
     * aggregate, audit, publish.
     */
    @Transactional
    public DispatchItemResponse finalizeDispatch(ReportDispatchItemEntity item,
            List<ReportDeliveryAttemptEntity> completedAttempts, String auditAction,
            String eventType, String auditDetailJson, String ipAddress) {
        // The item returned from phase 1 is detached and its attempts collection can be
        // an uninitialized Hibernate proxy. Persist the completed attempts explicitly,
        // then aggregate from a fresh DB query inside this transaction.
        attemptRepository.saveAll(completedAttempts);
        ReportDispatchItemEntity managed = itemRepository.findById(item.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Dispatch item not found"));
        List<ReportDeliveryAttemptEntity> persistedAttempts = attemptRepository
                .findByDispatchItemIdOrderByCreatedAtAsc(managed.getId());
        managed.setOverallStatus(aggregateStatusFromAttempts(persistedAttempts, parsePreferredMethods(managed)));
        ReportDispatchItemEntity saved = itemRepository.save(managed);
        updateLinkedOrderIfDelivered(saved);

        auditService.log(auditAction, "REPORT_DISPATCH", saved.getId(), saved.getReportReference(),
                auditDetailJson, ipAddress);

        applicationEventPublisher.publishEvent(new ReportDispatchDomainEvent(
                eventType, saved.getReportReference(), saved.getBranchCode(),
                saved.getOverallStatus().name(), LocalDateTime.now()));

        return mapToDetailResponse(saved);
    }

    @Transactional
    public DispatchItemResponse markAttemptDelivered(UUID attemptId, String ipAddress) {
        ReportDeliveryAttemptEntity attempt = attemptRepository.findByIdWithItem(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery attempt not found"));

        ReportDispatchItemEntity item = attempt.getDispatchItem();
        assertBranchAccess(item.getBranchCode());

        if (attempt.getStatus() == DeliveryAttemptStatus.DELIVERED) {
            updateLinkedOrderIfDelivered(item);
            return mapToDetailResponse(item);
        }
        if (attempt.getStatus() == DeliveryAttemptStatus.FAILED) {
            throw new InvalidRequestException("Retry failed attempts before marking them delivered");
        }

        LocalDateTime now = LocalDateTime.now();
        if (attempt.getDispatchedAt() == null) {
            attempt.setDispatchedAt(now);
        }
        attempt.setStatus(DeliveryAttemptStatus.DELIVERED);
        attempt.setDeliveredAt(now);
        attempt.setFailureReason(null);

        List<ReportDeliveryAttemptEntity> persistedAttempts = attemptRepository
                .findByDispatchItemIdOrderByCreatedAtAsc(item.getId());
        item.setOverallStatus(aggregateStatusFromAttempts(persistedAttempts, parsePreferredMethods(item)));
        ReportDispatchItemEntity saved = itemRepository.save(item);
        updateLinkedOrderIfDelivered(saved);

        auditService.log(
                "DISPATCH_MARK_DELIVERED",
                "REPORT_DISPATCH",
                saved.getId(),
                saved.getReportReference(),
                "{\"attemptId\":\"" + attemptId + "\"}",
                ipAddress);

        applicationEventPublisher.publishEvent(new ReportDispatchDomainEvent(
                "DISPATCH_MARK_DELIVERED",
                saved.getReportReference(),
                saved.getBranchCode(),
                saved.getOverallStatus().name(),
                LocalDateTime.now()));

        return mapToDetailResponse(saved);
    }

    private void updateLinkedOrderIfDelivered(ReportDispatchItemEntity item) {
        if (item.getOverallStatus() != DispatchItemStatus.DELIVERED) {
            return;
        }

        UUID resultId;
        try {
            resultId = UUID.fromString(item.getReportReference());
        } catch (IllegalArgumentException ignored) {
            return;
        }

        Optional<TestResultEntity> result = testResultRepository.findById(resultId);
        if (result.isEmpty()) {
            return;
        }

        SampleEntity sample = result.get().getSample();
        if (sample == null || sample.getOrderItem() == null || sample.getOrderItem().getOrder() == null) {
            return;
        }

        OrderItemEntity orderItem = sample.getOrderItem();
        OrderEntity order = orderItem.getOrder();

        if (sample.getStatus() != SampleStatus.DISPATCHED) {
            sample.setStatus(SampleStatus.DISPATCHED);
            sampleRepository.save(sample);
        }
        if (orderItem.getStatus() != SampleStatus.DISPATCHED) {
            orderItem.setStatus(SampleStatus.DISPATCHED);
            orderItemRepository.save(orderItem);
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }

        boolean allItemsDispatched = order.getItems().stream()
                .filter(itemRow -> !itemRow.isDeleted())
                .allMatch(this::hasDispatchedActiveSample);

        if (allItemsDispatched && order.getStatus() != OrderStatus.COMPLETED) {
            order.setStatus(OrderStatus.COMPLETED);
            orderRepository.save(order);
        }
    }

    private boolean hasDispatchedActiveSample(OrderItemEntity orderItem) {
        return orderItem.getSamples().stream()
                .filter(sample -> !sample.isDeleted())
                .filter(sample -> sample.getStatus() != SampleStatus.REJECTED)
                .max(Comparator.comparing(SampleEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(sample -> sample.getStatus() == SampleStatus.DISPATCHED)
                .orElse(false);
    }

    /**
     * A dispatch item may only be registered for a result a pathologist has
     * actually signed, in the branch that owns it.
     *
     * <p>
     * reportReference is the TestResult id (see
     * {@code ClinicalAuthorizationService}, which builds the event with
     * {@code result.getId().toString()}). Without this check the endpoint accepted
     * any string, so a report could enter dispatch — and be emailed to the patient
     * — with no clinical authorization behind it, and an existing item could be
     * overwritten by reusing its reference.
     */
    private void assertResultClinicallyAuthorized(String reportReference, String branch) {
        UUID resultId;
        try {
            resultId = UUID.fromString(reportReference);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(
                    "reportReference must be the id of a clinically authorized result");
        }

        TestResultEntity result = testResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No test result for reportReference " + reportReference));

        if (result.getStatus() != ResultStatus.CLINICALLY_AUTHORIZED
                && result.getStatus() != ResultStatus.DISPATCHED) {
            throw new InvalidRequestException(
                    "Result " + reportReference + " has not been clinically authorized (status: "
                            + result.getStatus() + ")");
        }

        String resultBranch = (result.getSample() != null
                && result.getSample().getOrderItem() != null
                && result.getSample().getOrderItem().getOrder() != null)
                        ? result.getSample().getOrderItem().getOrder().getBranchCode()
                        : null;
        if (resultBranch == null || !resultBranch.equalsIgnoreCase(branch)) {
            throw new InvalidRequestException(
                    "Result " + reportReference + " does not belong to branch " + branch);
        }
    }

    private void assertRegisterBranchAllowed(String branch) {
        if (isSuperAdmin()) {
            return;
        }
        String mine = SecurityUtils.getCurrentBranchId();
        if (mine == null) {
            throw new InvalidRequestException("Missing branch in security context");
        }
        if (!mine.equalsIgnoreCase(branch)) {
            throw new InvalidRequestException("branchCode must match your assigned branch");
        }
    }

    private void assertBranchAccess(String itemBranch) {
        if (isSuperAdmin() || isDispatchRole()) {
            return;
        }
        String mine = SecurityUtils.getCurrentBranchId();
        if (mine == null || !mine.equalsIgnoreCase(itemBranch)) {
            throw new InvalidRequestException("You cannot access this branch's dispatch data");
        }
    }

    private ReportDispatchItemEntity loadItemForCurrentUser(String reportReference, String branchCodeParam) {
        if (isSuperAdmin() || isDispatchRole()) {
            if (branchCodeParam != null && !branchCodeParam.isBlank()) {
                return itemRepository
                        .findByReportReferenceAndBranchCode(reportReference, branchCodeParam.trim().toUpperCase())
                        .orElseThrow(() -> new ResourceNotFoundException("Dispatch item not found"));
            }
            return itemRepository.findFirstByReportReferenceOrderByAuthorizedAtDesc(reportReference)
                    .orElseThrow(() -> new ResourceNotFoundException("Dispatch item not found"));
        }
        String branch = SecurityUtils.getCurrentBranchId();
        if (branch == null) {
            throw new InvalidRequestException("Missing branch in security context");
        }
        ReportDispatchItemEntity item = itemRepository
                .findByReportReferenceAndBranchCode(reportReference, branch.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Dispatch item not found"));
        if (branchCodeParam != null && !branchCodeParam.isBlank()
                && !branch.equalsIgnoreCase(branchCodeParam.trim())) {
            throw new InvalidRequestException("branchCode does not match your session");
        }
        return item;
    }

    /**
     * Empty = no branch filter (all branches, super admin only).
     */
    private Optional<String> resolveBranchFilterForQuery(String branchCodeParam) {
        if (isSuperAdmin() || isDispatchRole()) {
            if (branchCodeParam != null && !branchCodeParam.isBlank()) {
                return Optional.of(branchCodeParam.trim().toUpperCase());
            }
            return Optional.empty();
        }
        String mine = SecurityUtils.getCurrentBranchId();
        if (mine == null) {
            throw new InvalidRequestException("Missing branch in security context");
        }
        return Optional.of(mine.toUpperCase());
    }

    private boolean isDispatchRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_DISPATCH".equals(a.getAuthority())
                || "ROLE_DISPATCH_OFFICER".equals(a.getAuthority())
                || "ROLE_REPORT_DISPATCH".equals(a.getAuthority()));
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private static Pageable buildPageable(int page, int size, String sort, String defaultField) {
        String[] parts = sort.split(",");
        String prop = parts[0].trim();
        if (!List.of("authorizedAt", "createdAt", "reportReference").contains(prop)) {
            prop = defaultField;
        }
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return PageRequest.of(page, size, Sort.by(dir, prop));
    }

    private static Specification<ReportDispatchItemEntity> dispatchListSpec(
            Optional<String> branch,
            Optional<DispatchItemStatus> status,
            Optional<String> keyword) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            branch.ifPresent(b -> predicates.add(cb.equal(root.get("branchCode"), b)));
            status.ifPresent(s -> {
                if (s == DispatchItemStatus.PENDING) {
                    predicates
                            .add(root.get("overallStatus").in(DispatchItemStatus.PENDING, DispatchItemStatus.PARTIAL));
                } else {
                    predicates.add(cb.equal(root.get("overallStatus"), s));
                }
            });
            keyword.filter(k -> !k.isBlank()).ifPresent(kw -> {
                String pattern = "%" + kw.toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("reportReference")), pattern),
                        cb.like(cb.lower(root.get("patientCode")), pattern),
                        cb.like(cb.lower(root.get("patientDisplayName")), pattern),
                        cb.like(cb.lower(root.get("testPanelLabel")), pattern)));
            });
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Specification<ReportDispatchItemEntity> deliveryRecordsSpec(
            Optional<String> branch,
            Optional<DispatchItemStatus> status,
            Optional<String> keyword) {

        return (root, query, cb) -> {
            query.distinct(true);
            root.join("attempts", JoinType.INNER);
            List<Predicate> predicates = new ArrayList<>();
            branch.ifPresent(b -> predicates.add(cb.equal(root.get("branchCode"), b)));
            status.ifPresent(s -> {
                if (s == DispatchItemStatus.PENDING) {
                    predicates
                            .add(root.get("overallStatus").in(DispatchItemStatus.PENDING, DispatchItemStatus.PARTIAL));
                } else {
                    predicates.add(cb.equal(root.get("overallStatus"), s));
                }
            });
            keyword.filter(k -> !k.isBlank()).ifPresent(kw -> {
                String pattern = "%" + kw.toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("reportReference")), pattern),
                        cb.like(cb.lower(root.get("patientCode")), pattern),
                        cb.like(cb.lower(root.get("patientDisplayName")), pattern),
                        cb.like(cb.lower(root.get("testPanelLabel")), pattern)));
            });
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private DispatchDashboardItemResponse toDashboardItem(ReportDispatchItemEntity e) {
        ZonedDateTime z = e.getAuthorizedAt().atZone(DISPLAY_ZONE);
        String authorizedBy = null;
        String priorityLevel = null;
        try {
            UUID resultId = UUID.fromString(e.getReportReference());
            Optional<TestResultEntity> tr = testResultRepository.findById(resultId);
            if (tr.isPresent()) {
                authorizedBy = tr.get().getClinicallyAuthorizedBy();
                if (tr.get().getSample() != null && tr.get().getSample().getPriority() != null) {
                    priorityLevel = tr.get().getSample().getPriority().name();
                }
            }
        } catch (Exception ignored) {
        }

        return DispatchDashboardItemResponse.builder()
                .id(e.getId())
                .reportId(e.getReportReference())
                .patientName(e.getPatientDisplayName())
                .patientId(Optional.ofNullable(e.getPatientCode()).orElse(""))
                .testName(e.getTestPanelLabel())
                .authorizedDate(z.format(DATE_FMT))
                .authorizedTime(z.format(TIME_FMT))
                .deliveryMethods(parsePreferredMethods(e))
                .status(e.getOverallStatus())
                .authorizedBy(authorizedBy)
                .priorityLevel(priorityLevel)
                .build();
    }

    private DeliveryRecordResponse toDeliveryRecord(ReportDispatchItemEntity item) {
        List<ReportDeliveryAttemptEntity> attempts = attemptRepository
                .findByDispatchItemIdOrderByCreatedAtAsc(item.getId());
        List<DeliveryMethod> methods = attempts.stream()
                .map(ReportDeliveryAttemptEntity::getMethod)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();

        LocalDateTime minDisp = attempts.stream()
                .map(ReportDeliveryAttemptEntity::getDispatchedAt)
                .filter(d -> d != null)
                .min(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime maxDel = attempts.stream()
                .map(ReportDeliveryAttemptEntity::getDeliveredAt)
                .filter(d -> d != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        DispatchItemStatus rowStatus = aggregateStatusFromAttempts(attempts, parsePreferredMethods(item));
        String deliveredDisplay = (rowStatus == DispatchItemStatus.DELIVERED && maxDel != null)
                ? maxDel.atZone(DISPLAY_ZONE).format(RECORD_TS)
                : null;
        ReportDeliveryAttemptEntity trackedAttempt = attempts.stream()
                .filter(a -> a.getTrackingNumber() != null && !a.getTrackingNumber().isBlank())
                .reduce((first, second) -> second)
                .orElse(null);

        String authorizedBy = null;
        try {
            UUID resultId = UUID.fromString(item.getReportReference());
            Optional<TestResultEntity> tr = testResultRepository.findById(resultId);
            if (tr.isPresent()) {
                authorizedBy = tr.get().getClinicallyAuthorizedBy();
            }
        } catch (Exception ignored) {
        }

        Instant lastMod = Optional.ofNullable(item.getLastModifiedAt()).orElse(item.getCreatedAt());
        String updatedAtStr = lastMod != null
                ? lastMod.atZone(DISPLAY_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : null;
        String dispatchedBy = item.getLastModifiedBy() != null && !item.getLastModifiedBy().isBlank()
                ? item.getLastModifiedBy()
                : item.getCreatedBy();

        return DeliveryRecordResponse.builder()
                .reportId(item.getReportReference())
                .patientName(item.getPatientDisplayName())
                .patientCode(item.getPatientCode())
                .testName(item.getTestPanelLabel())
                .authorizedBy(authorizedBy)
                .dispatchedBy(dispatchedBy)
                .methods(methods)
                .status(rowStatus)
                .dispatchedTime(minDisp != null ? minDisp.atZone(DISPLAY_ZONE).format(RECORD_TS) : "—")
                .deliveredTime(deliveredDisplay)
                .trackingNumber(trackedAttempt != null ? trackedAttempt.getTrackingNumber() : null)
                .trackingUrl(trackedAttempt != null ? trackedAttempt.getTrackingUrl() : null)
                .updatedAt(updatedAtStr)
                .build();
    }

    private FailedDeliveryResponse toFailedDelivery(ReportDeliveryAttemptEntity a) {
        ReportDispatchItemEntity i = a.getDispatchItem();
        Instant when = Optional.ofNullable(a.getLastModifiedAt()).orElse(a.getCreatedAt());
        DispatchReportPayload reportPayload = resolveReportPayload(i);
        String patientCode = i.getPatientCode() != null && !i.getPatientCode().isBlank()
                ? i.getPatientCode()
                : (reportPayload != null ? reportPayload.patientCode() : null);
        String dispatchedBy = a.getLastModifiedBy() != null && !a.getLastModifiedBy().isBlank()
                ? a.getLastModifiedBy()
                : (i.getLastModifiedBy() != null && !i.getLastModifiedBy().isBlank()
                        ? i.getLastModifiedBy()
                        : (i.getCreatedBy() != null ? i.getCreatedBy() : (reportPayload != null ? reportPayload.authorizedBy() : "System")));

        return FailedDeliveryResponse.builder()
                .attemptId(a.getId())
                .reportId(i.getReportReference())
                .patientName(i.getPatientDisplayName() != null && !i.getPatientDisplayName().isBlank()
                        ? i.getPatientDisplayName()
                        : (reportPayload != null ? reportPayload.patientName() : "Patient"))
                .patientCode(patientCode)
                .testName(i.getTestPanelLabel())
                .method(a.getMethod())
                .failureReason(Optional.ofNullable(a.getFailureReason()).orElse("Unknown failure"))
                .failedDateTime(when.atZone(DISPLAY_ZONE).format(RECORD_TS))
                .retryCount(a.getRetryCount())
                .dispatchedBy(dispatchedBy)
                .recipientContact(a.getRecipientContact())
                .build();
    }

    private DispatchItemResponse mapToDetailResponse(ReportDispatchItemEntity e) {
        List<ReportDeliveryAttemptEntity> attempts = attemptRepository
                .findByDispatchItemIdOrderByCreatedAtAsc(e.getId());
        List<DeliveryAttemptResponse> attemptDtos = attempts.stream().map(this::toAttemptDto).toList();
        DispatchReportPayload reportPayload = resolveReportPayload(e);
        return DispatchItemResponse.builder()
                .id(e.getId())
                .reportReference(e.getReportReference())
                .reportNo(e.getReportNo())
                .branchCode(e.getBranchCode())
                .patientCode(Optional.ofNullable(reportPayload.patientCode()).orElse(e.getPatientCode()))
                .patientDisplayName(Optional.ofNullable(reportPayload.patientName()).orElse(e.getPatientDisplayName()))
                .patientAge(reportPayload.patientAge())
                .patientGender(reportPayload.patientGender())
                .patientDob(reportPayload.patientDob())
                .referringDoctor(reportPayload.referringDoctor())
                .ward(reportPayload.ward())
                .testPanelLabel(e.getTestPanelLabel())
                .sampleId(reportPayload.sampleId())
                .sampleCollectedAt(reportPayload.sampleCollectedAt())
                .reportGeneratedAt(reportPayload.reportGeneratedAt())
                .authorizedBy(reportPayload.authorizedBy())
                .clinicalNote(reportPayload.clinicalNote())
                .results(reportPayload.results())
                .artifactUri(e.getArtifactUri())
                .authorizedAt(e.getAuthorizedAt().atZone(DISPLAY_ZONE).toOffsetDateTime())
                .overallStatus(e.getOverallStatus())
                .preferredDeliveryMethods(parsePreferredMethods(e))
                .attempts(attemptDtos)
                .build();
    }

    private DispatchReportPayload resolveReportPayload(ReportDispatchItemEntity item) {
        UUID resultId;
        try {
            resultId = UUID.fromString(item.getReportReference());
        } catch (IllegalArgumentException ignored) {
            return DispatchReportPayload.empty();
        }

        Optional<TestResultEntity> anchorResult = testResultRepository.findById(resultId);
        if (anchorResult.isEmpty()) {
            return DispatchReportPayload.empty();
        }

        TestResultEntity anchor = anchorResult.get();
        SampleEntity sample = anchor.getSample();
        OrderItemEntity orderItem = sample == null ? null : sample.getOrderItem();
        OrderEntity order = orderItem == null ? null : orderItem.getOrder();

        String patientCode = order == null ? item.getPatientCode() : trimToNull(order.getPatientId());
        PatientEntity patient = patientCode == null
                ? null
                : patientRepository.findByPatientCode(patientCode).orElse(null);

        List<TestResultEntity> sampleResults = sample == null
                ? List.of(anchor)
                : testResultRepository.findBySampleId(sample.getId()).stream()
                        .filter(result -> !result.isDeleted())
                        .filter(result -> !Boolean.TRUE.equals(result.getDraft()))
                        .sorted(Comparator
                                .comparing((TestResultEntity result) -> result.getParameter().getDisplayOrder(),
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(result -> result.getParameter().getName(),
                                        String.CASE_INSENSITIVE_ORDER))
                        .toList();

        String authorizedBy = firstNonBlank(sampleResults.stream()
                .map(TestResultEntity::getClinicallyAuthorizedBy)
                .toList());
        String clinicalNote = firstNonBlank(sampleResults.stream()
                .map(TestResultEntity::getClinicalNote)
                .toList());
        OffsetDateTime authorizedAt = anchor.getClinicallyAuthorizedAt() == null
                ? item.getAuthorizedAt().atZone(DISPLAY_ZONE).toOffsetDateTime()
                : anchor.getClinicallyAuthorizedAt().atZone(DISPLAY_ZONE).toOffsetDateTime();

        return new DispatchReportPayload(
                patient == null ? patientCode : patient.getPatientCode(),
                patient == null ? null : patient.getFullName(),
                patient == null ? null : calculateAge(patient.getDob()),
                patient == null || patient.getGender() == null ? null : patient.getGender().name(),
                patient == null ? null : patient.getDob(),
                order == null ? null : trimToNull(order.getReferringDoctor()),
                order == null ? null : trimToNull(order.getReferringDepartment()),
                sample == null ? null : sample.getBarcode(),
                sample == null || sample.getCollectedAt() == null ? null
                        : sample.getCollectedAt().atZone(DISPLAY_ZONE).toOffsetDateTime(),
                authorizedAt,
                authorizedBy,
                clinicalNote,
                sampleResults.stream().map(this::toDispatchResult).toList());
    }

    private DispatchReportResultResponse toDispatchResult(TestResultEntity result) {
        String flag = result.getFlag() == null ? null : result.getFlag().name();
        return DispatchReportResultResponse.builder()
                .parameter(result.getParameter().getName())
                .result(result.getResultValue())
                .unit(result.getParameter().getUnit())
                .flag(flag)
                .referenceRange(
                        formatReferenceRange(result.getParameter().getRefLow(), result.getParameter().getRefHigh()))
                .abnormal(flag != null && !"NORMAL".equals(flag))
                .build();
    }

    private String formatReferenceRange(BigDecimal low, BigDecimal high) {
        if (low == null && high == null) {
            return null;
        }
        if (low == null) {
            return "<= " + formatDecimal(high);
        }
        if (high == null) {
            return ">= " + formatDecimal(low);
        }
        return formatDecimal(low) + " - " + formatDecimal(high);
    }

    private String formatDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private Integer calculateAge(LocalDate dob) {
        return dob == null ? null : Period.between(dob, LocalDate.now(DISPLAY_ZONE)).getYears();
    }

    private String firstNonBlank(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }

    private record DispatchReportPayload(
            String patientCode,
            String patientName,
            Integer patientAge,
            String patientGender,
            LocalDate patientDob,
            String referringDoctor,
            String ward,
            String sampleId,
            OffsetDateTime sampleCollectedAt,
            OffsetDateTime reportGeneratedAt,
            String authorizedBy,
            String clinicalNote,
            List<DispatchReportResultResponse> results) {
        static DispatchReportPayload empty() {
            return new DispatchReportPayload(null, null, null, null, null, null, null, null, null, null, null, null,
                    List.of());
        }
    }

    private DeliveryAttemptResponse toAttemptDto(ReportDeliveryAttemptEntity a) {
        return DeliveryAttemptResponse.builder()
                .id(a.getId())
                .method(a.getMethod())
                .status(a.getStatus())
                .failureReason(a.getFailureReason())
                .retryCount(a.getRetryCount())
                .dispatchedAt(a.getDispatchedAt() != null ? a.getDispatchedAt().atZone(DISPLAY_ZONE).toOffsetDateTime()
                        : null)
                .deliveredAt(
                        a.getDeliveredAt() != null ? a.getDeliveredAt().atZone(DISPLAY_ZONE).toOffsetDateTime() : null)
                .recipientContact(a.getRecipientContact())
                .trackingNumber(a.getTrackingNumber())
                .trackingUrl(a.getTrackingUrl())
                .build();
    }

    private List<DeliveryMethod> parsePreferredMethods(ReportDispatchItemEntity e) {
        if (e.getPreferredMethodsJson() == null || e.getPreferredMethodsJson().isBlank()) {
            return DEFAULT_DELIVERY_METHODS;
        }
        try {
            List<DeliveryMethod> methods = objectMapper.readValue(e.getPreferredMethodsJson(), new TypeReference<>() {
            });
            return methods == null || methods.isEmpty() ? DEFAULT_DELIVERY_METHODS : methods;
        } catch (Exception ex) {
            log.warn("Invalid preferred_methods JSON for {}", e.getReportReference());
            return DEFAULT_DELIVERY_METHODS;
        }
    }

    private static DispatchItemStatus aggregateStatusFromAttempts(
            List<ReportDeliveryAttemptEntity> attempts, List<DeliveryMethod> activeMethods) {
        if (attempts == null || attempts.isEmpty() || activeMethods == null || activeMethods.isEmpty()) {
            return DispatchItemStatus.PENDING;
        }

        // Attempts are returned oldest-first. Only the latest attempt for each
        // currently selected channel represents the present delivery state;
        // historical failures/pending rows remain available for audit but must not
        // keep a later successful dispatch permanently PARTIAL.
        java.util.Map<DeliveryMethod, ReportDeliveryAttemptEntity> latest = new java.util.LinkedHashMap<>();
        attempts.stream()
                .filter(attempt -> activeMethods.contains(attempt.getMethod()))
                .forEach(attempt -> latest.put(attempt.getMethod(), attempt));
        List<ReportDeliveryAttemptEntity> current = List.copyOf(latest.values());
        if (current.isEmpty())
            return DispatchItemStatus.PENDING;

        boolean anyDelivered = current.stream().anyMatch(a -> a.getStatus() == DeliveryAttemptStatus.DELIVERED);
        boolean anyFailed = current.stream().anyMatch(a -> a.getStatus() == DeliveryAttemptStatus.FAILED);
        boolean anyOpen = current.stream().anyMatch(
                a -> a.getStatus() == DeliveryAttemptStatus.PENDING || a.getStatus() == DeliveryAttemptStatus.SENT);

        boolean allDelivered = current.size() == activeMethods.stream().distinct().count()
                && current.stream().allMatch(a -> a.getStatus() == DeliveryAttemptStatus.DELIVERED);
        if (allDelivered) {
            return DispatchItemStatus.DELIVERED;
        }
        if (anyFailed && !anyDelivered && !anyOpen) {
            return DispatchItemStatus.FAILED;
        }
        if (anyFailed && (anyDelivered || anyOpen)) {
            return DispatchItemStatus.PARTIAL;
        }
        if (anyOpen && anyDelivered) {
            return DispatchItemStatus.PARTIAL;
        }
        if (anyOpen) {
            return DispatchItemStatus.PENDING;
        }
        return DispatchItemStatus.PARTIAL;
    }

    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
