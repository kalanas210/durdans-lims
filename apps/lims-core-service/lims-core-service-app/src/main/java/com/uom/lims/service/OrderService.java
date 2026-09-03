package com.uom.lims.service;

import com.uom.lims.api.dto.request.OrderCreateRequest;
import com.uom.lims.api.dto.response.OrderItemResponse;
import com.uom.lims.api.dto.response.OrderResponse;
import com.uom.lims.api.dto.response.OrderTrackingEventResponse;
import com.uom.lims.api.dto.response.OrderTrackingResponse;
import com.uom.lims.api.dto.response.OrderTrackingStepResponse;
import com.uom.lims.api.dispatch.enums.DeliveryAttemptStatus;
import com.uom.lims.api.dispatch.enums.DispatchItemStatus;
import com.uom.lims.api.enums.OrderStatus;
import com.uom.lims.api.enums.PaymentStatus;
import com.uom.lims.api.enums.Priority;
import com.uom.lims.api.enums.SampleStatus;
import com.uom.lims.api.verification.enums.ResultStatus;
import com.uom.lims.audit.AuditLog;
import com.uom.lims.audit.AuditLogRepository;
import com.uom.lims.api.patient.dto.response.PatientResponse;
import com.uom.lims.config.BillingProperties;
import com.uom.lims.dispatch.ReportDispatchItemRepository;
import com.uom.lims.dispatch.ReportDeliveryAttemptEntity;
import com.uom.lims.dispatch.ReportDeliveryAttemptRepository;
import com.uom.lims.dispatch.ReportDispatchItemEntity;
import com.uom.lims.entity.BillEntity;
import com.uom.lims.entity.OrderEntity;
import com.uom.lims.entity.OrderItemEntity;
import com.uom.lims.entity.SampleEntity;
import com.uom.lims.entity.TestCatalogEntity;
import com.uom.lims.entity.TestResultEntity;
import com.uom.lims.exception.DuplicateResourceException;
import com.uom.lims.exception.InvalidStateTransitionException;
import com.uom.lims.exception.ResourceNotFoundException;
import com.uom.lims.repository.BillRepository;
import com.uom.lims.repository.OrderRepository;
import com.uom.lims.repository.OrderSpecifications;
import com.uom.lims.repository.SampleRepository;
import com.uom.lims.repository.TestCatalogRepository;
import com.uom.lims.repository.TestResultRepository;
import com.uom.lims.notification.PatientLifecycleNotificationService;
import com.uom.lims.util.ReferenceNumberGenerator;
import com.uom.lims.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * WHY: Orchestrates the full lifecycle of a clinical order from test selection
 * through
 * billing and sample creation. Keeping this orchestration in one service
 * ensures
 * that order, bill, and sample are always created atomically — no partial
 * records
 * can exist in the database if any step fails (@Transactional rolls back all).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

        private final OrderRepository orderRepository;
        private final TestCatalogRepository testCatalogRepository;
        private final BillRepository billRepository;
        private final SampleRepository sampleRepository;
        private final ReferenceNumberGenerator referenceNumberGenerator;
        private final PatientClientService patientClientService;
        private final BillingProperties billingProperties;
        private final TestResultRepository testResultRepository;
        private final ReportDispatchItemRepository dispatchItemRepository;
        private final ReportDeliveryAttemptRepository deliveryAttemptRepository;
        private final AuditLogRepository auditLogRepository;
        private final com.uom.lims.audit.AuditService auditService;
        private final PatientLifecycleNotificationService patientLifecycleNotificationService;

        /**
         * WHY: Creating an order is the entry point for the entire clinical workflow.
         * The method is intentionally structured as sequential numbered steps matching
         * the clinical process so future maintainers can trace each domain action
         * clearly.
         * All 9 steps run atomically — a billing failure rolls back the order record
         * too.
         *
         * @param request the order creation payload from the receptionist
         * @return the created order mapped to a response DTO
         */
        public OrderResponse createOrder(OrderCreateRequest request) {
                log.info("Creating order for patient {} by user {}", request.getPatientId(),
                                SecurityUtils.getCurrentUsername());

                // Step 1: Resolve test catalog entries — fail fast if any testId is invalid or
                // inactive.
                // WHY: Using findAllByIdInAndActiveTrueAndDeletedFalse avoids separate per-test
                // queries.
                // A Map is built keyed by ID so items can be enriched without parallel-index
                // assumptions.
                List<UUID> testUUIDs = request.getTestIds().stream()
                                .map(UUID::fromString)
                                .toList();

                List<TestCatalogEntity> tests = testCatalogRepository
                                .findAllByIdInAndActiveTrueAndDeletedFalse(testUUIDs);

                if (tests.size() != testUUIDs.size()) {
                        throw new ResourceNotFoundException(
                                        "One or more test IDs not found, inactive, or deleted in the catalog");
                }

                // WHY: Map by UUID so order item enrichment (Step 9 toResponse) is O(1) lookup
                // rather than a nested O(n²) stream scan.
                Map<UUID, TestCatalogEntity> testMap = tests.stream()
                                .collect(Collectors.toMap(TestCatalogEntity::getId, Function.identity()));

                // Step 2: Prevent duplicate tests across the patient's active orders.
                // WHY: A patient may have multiple concurrent active orders as long as no
                // test is duplicated — duplicating the same test in two active orders would
                // create ambiguous sample routing and potential double-billing.
                List<OrderEntity> activeOrders = orderRepository.findAllByPatientIdAndStatusInAndDeletedFalse(
                                request.getPatientId(), List.of(OrderStatus.PENDING, OrderStatus.IN_PROGRESS));

                Set<UUID> alreadyOrderedTestIds = activeOrders.stream()
                                .flatMap(o -> o.getItems().stream())
                                .map(OrderItemEntity::getTestId)
                                .collect(Collectors.toCollection(HashSet::new));

                List<UUID> overlapping = testUUIDs.stream()
                                .filter(alreadyOrderedTestIds::contains)
                                .toList();

                if (!overlapping.isEmpty()) {
                        // Build a human-readable list of the conflicting test codes for the error
                        // message.
                        String conflictingCodes = overlapping.stream()
                                        .map(id -> testMap.containsKey(id) ? testMap.get(id).getTestCode()
                                                        : id.toString())
                                        .collect(Collectors.joining(", "));
                        throw new DuplicateResourceException(
                                        "Patient " + request.getPatientId()
                                                        + " already has an active order containing the following test(s): "
                                                        + conflictingCodes);
                }

                // Step 3: Build the order aggregate root.
                Map<String, Priority> testPriorities = request.getTestPriorities() != null
                                ? request.getTestPriorities()
                                : Map.of();
                Priority orderPriority = testUUIDs.stream()
                                .map(id -> testPriorities.getOrDefault(id.toString(),
                                                request.getPriority() != null ? request.getPriority()
                                                                : Priority.NORMAL))
                                .min(Comparator.comparingInt(OrderService::priorityRank))
                                .orElse(Priority.NORMAL);

                OrderEntity order = new OrderEntity();
                order.setOrderNo(referenceNumberGenerator.generateOrderNo());
                order.setPatientId(request.getPatientId());
                order.setPriority(orderPriority);
                order.setReferringDoctor(request.getReferringDoctor());
                order.setReferringDepartment(request.getReferringDepartment());
                order.setRemarks(request.getRemarks());
                order.setStatus(OrderStatus.PENDING);
                order.setCreatedBy(SecurityUtils.getCurrentUsername());
                // Stamp the owning branch so order/bill reads can be tenant-scoped.
                order.setBranchCode(SecurityUtils.getCurrentBranchId());

                // Step 4: Build one OrderItemEntity per test, snapshotting the catalog price.
                List<OrderItemEntity> items = new ArrayList<>();
                for (TestCatalogEntity test : tests) {
                        OrderItemEntity item = new OrderItemEntity();
                        item.setOrder(order);
                        item.setTestId(test.getId());
                        item.setPrice(test.getPrice());
                        item.setStatus(SampleStatus.PENDING_COLLECTION);
                        item.setCreatedBy(SecurityUtils.getCurrentUsername());
                        items.add(item);
                }
                order.setItems(items);

                // Step 5: Persist the order with its items cascade.
                OrderEntity savedOrder = orderRepository.save(order);

                // Step 6: Calculate billing amounts using BigDecimal to avoid floating-point
                // errors.
                // WHY: RoundingMode.HALF_UP is standard for monetary values (matches bank
                // rounding).
                BigDecimal subtotal = items.stream()
                                .map(OrderItemEntity::getPrice)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal serviceChargeRate = billingProperties.getServiceChargePercentage()
                                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                BigDecimal serviceCharge = subtotal.multiply(serviceChargeRate)
                                .setScale(2, RoundingMode.HALF_UP);
                BigDecimal totalAmount = subtotal.add(serviceCharge);

                // Step 7: Create the bill linked to the order.
                BillEntity bill = new BillEntity();
                bill.setOrder(savedOrder);
                bill.setBillNo(referenceNumberGenerator.generateBillNo());
                bill.setSubtotal(subtotal);
                bill.setServiceCharge(serviceCharge);
                bill.setDiscount(BigDecimal.ZERO);
                bill.setTotalAmount(totalAmount);
                bill.setPaidAmount(BigDecimal.ZERO);
                bill.setPaymentStatus(PaymentStatus.PENDING);
                bill.setCreatedBy(SecurityUtils.getCurrentUsername());
                billRepository.save(bill);

                // Step 8: Create one SampleEntity per order item with a unique barcode.
                // WHY: Each test may require a different tube type — separate sample records
                // allow the phlebotomist to label and track each tube independently.
                // Using testMap lookup (not parallel index) prevents mismatched item-to-test
                // assignments.
                for (OrderItemEntity item : savedOrder.getItems()) {
                        TestCatalogEntity test = testMap.get(item.getTestId());
                        Priority samplePriority = testPriorities.getOrDefault(item.getTestId().toString(),
                                        savedOrder.getPriority());
                        SampleEntity sample = new SampleEntity();
                        sample.setOrderItem(item);
                        sample.setBarcode(referenceNumberGenerator.generateBarcode());
                        sample.setTubeType(test.getTubeType());
                        sample.setPriority(samplePriority);
                        sample.setStatus(SampleStatus.PENDING_COLLECTION);
                        sample.setRecollectionCount(0);
                        sample.setCreatedBy(SecurityUtils.getCurrentUsername());
                        sampleRepository.save(sample);
                }

                log.info("Order {} created successfully with bill for patient {}",
                                savedOrder.getOrderNo(), savedOrder.getPatientId());

                // Published inside the transaction and sent only AFTER_COMMIT. A rollback
                // can never leave the patient with a tracking message for a missing order.
                patientLifecycleNotificationService.orderCreated(savedOrder, bill);

                String details = String.format("{\"patientId\":\"%s\", \"priority\":\"%s\", \"billNo\":\"%s\"}", savedOrder.getPatientId(), savedOrder.getPriority(), bill.getBillNo());
                auditService.log("CREATE_ORDER", "ORDER", savedOrder.getId(), savedOrder.getPatientId(), details, getCurrentIp());

                // Step 9: Map the saved entity to a response DTO, enriching items with test
                // catalog data.
                return toResponse(savedOrder, testMap);
        }

        private static int priorityRank(Priority priority) {
                return switch (priority) {
                        case STAT -> 0;
                        case URGENT -> 1;
                        case NORMAL -> 2;
                };
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

        /**
         * WHY: Paginated retrieval with deletedFalse filter prevents soft-deleted
         * orders
         * from appearing in administrative lists — controllers never receive raw entity
         * lists.
         *
         * @param pageable pagination and sorting parameters from the request
         * @return paginated page of OrderResponse DTOs
         */
        @Transactional
        public Page<OrderResponse> getOrders(Pageable pageable) {
                return getOrders(pageable, null);
        }

        @Transactional
        public Page<OrderResponse> getOrders(Pageable pageable, String patientId) {
                String normalizedPatientId = patientId == null ? null : patientId.trim();
                // Tenant isolation: restrict to the caller's branch (all branches only
                // for SUPER_ADMIN). resolveBranchScope() fails closed.
                Specification<OrderEntity> spec = OrderSpecifications.notDeleted()
                                .and(OrderSpecifications.forPatient(normalizedPatientId))
                                .and(OrderSpecifications.inBranch(SecurityUtils.resolveBranchScope()));
                Page<OrderEntity> orders = orderRepository.findAll(spec, pageable);
                return orders
                                .map(order -> {
                                        reconcileOrderCompletionFromDispatch(order);
                                        // Fetch test catalog for enrichment
                                        List<UUID> testIds = order.getItems().stream()
                                                        .map(OrderItemEntity::getTestId).toList();
                                        Map<UUID, TestCatalogEntity> testMap = testCatalogRepository
                                                        .findAllById(testIds).stream()
                                                        .collect(Collectors.toMap(TestCatalogEntity::getId,
                                                                        Function.identity()));
                                        return toResponse(order, testMap);
                                });
        }

        /**
         * Tenant isolation guard for a single order: a non-super-admin may only
         * read an order in their own branch. Cross-branch access is reported as
         * not-found so order existence is not revealed by enumeration.
         */
        private void assertBranchAccess(OrderEntity order) {
                if (!SecurityUtils.canAccessBranch(order.getBranchCode())) {
                        throw new ResourceNotFoundException(
                                        "Order not found with id: " + order.getId());
                }
        }

        /**
         * WHY: Lookup by UUID is the primary retrieval path. Using UUID prevents
         * sequential ID enumeration.
         *
         * @param id the internal UUID of the order
         * @return the matching order as a response DTO
         * @throws ResourceNotFoundException if the order does not exist
         */
        @Transactional
        public OrderResponse getOrderById(UUID id) {
                OrderEntity order = orderRepository.findByIdAndDeletedFalse(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));
                assertBranchAccess(order);
                reconcileOrderCompletionFromDispatch(order);

                // Fetch test catalog for enrichment
                List<UUID> testIds = order.getItems().stream()
                                .map(OrderItemEntity::getTestId).toList();
                Map<UUID, TestCatalogEntity> testMap = testCatalogRepository
                                .findAllById(testIds).stream()
                                .collect(Collectors.toMap(TestCatalogEntity::getId, Function.identity()));

                return toResponse(order, testMap);
        }

        @Transactional
        public OrderTrackingResponse getOrderTracking(UUID id) {
                OrderEntity order = orderRepository.findByIdAndDeletedFalse(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));
                assertBranchAccess(order);
                reconcileOrderCompletionFromDispatch(order);

                List<UUID> testIds = order.getItems().stream()
                                .filter(item -> !item.isDeleted())
                                .map(OrderItemEntity::getTestId)
                                .toList();
                Map<UUID, TestCatalogEntity> testMap = testCatalogRepository.findAllById(testIds).stream()
                                .collect(Collectors.toMap(TestCatalogEntity::getId, Function.identity()));

                List<TrackingEvent> events = new ArrayList<>();
                events.add(new TrackingEvent(
                                "ORDER",
                                "Order created",
                                "Billing created laboratory order " + order.getOrderNo() + ".",
                                "COMPLETED",
                                instantText(order.getCreatedAt()),
                                order.getCreatedBy(),
                                null,
                                null,
                                null,
                                null,
                                null));

                List<SampleEntity> samples = order.getItems().stream()
                                .filter(item -> !item.isDeleted())
                                .flatMap(item -> item.getSamples().stream())
                                .filter(sample -> !sample.isDeleted())
                                .sorted(Comparator.comparing(SampleEntity::getCreatedAt,
                                                Comparator.nullsLast(Comparator.naturalOrder())))
                                .toList();

                List<UUID> sampleIds = samples.stream().map(SampleEntity::getId).toList();
                List<TestResultEntity> results = sampleIds.isEmpty()
                                ? List.of()
                                : testResultRepository.findBySampleIdIn(sampleIds).stream()
                                                .filter(result -> !result.isDeleted())
                                                .toList();
                Map<UUID, List<TestResultEntity>> resultsBySampleId = results.stream()
                                .collect(Collectors.groupingBy(result -> result.getSample().getId()));

                appendSampleEvents(events, samples, testMap);
                appendMltEvents(events, samples, resultsBySampleId, testMap);
                appendAuditEvents(events, sampleIds, "SAMPLE_ACCESSIONING",
                                List.of("ACCEPTED", "REJECTED"), testMap, samples);
                appendVerificationEvents(events, results, testMap);
                appendDispatchEvents(events, results);

                List<TrackingEvent> sortedEvents = events.stream()
                                .filter(event -> event.timestamp() != null)
                                .sorted(Comparator.comparing(TrackingEvent::timestamp,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                                .reversed())
                                .toList();

                TrackingEvent latest = sortedEvents.stream().findFirst().orElse(null);
                TrackingEvent current = sortedEvents.stream()
                                .filter(event -> "CURRENT".equals(event.status()))
                                .findFirst()
                                .orElse(latest);

                List<OrderTrackingStepResponse> steps = buildTrackingSteps(events, sortedEvents, order.getStatus());

                return OrderTrackingResponse.builder()
                                .orderId(order.getId())
                                .orderNo(order.getOrderNo())
                                .orderStatus(order.getStatus())
                                .currentStage(current != null ? current.title() : "Order created")
                                .currentDescription(current != null ? current.description() : null)
                                .steps(steps)
                                .events(sortedEvents.stream()
                                                .map(event -> OrderTrackingEventResponse.builder()
                                                                .id(UUID.randomUUID().toString())
                                                                .stage(event.stage())
                                                                .title(event.title())
                                                                .description(event.description())
                                                                .status(event.status())
                                                                .timestamp(event.timestamp())
                                                                .performedBy(event.performedBy())
                                                                .testName(event.testName())
                                                                .barcode(event.barcode())
                                                                .method(event.method())
                                                                .trackingNumber(event.trackingNumber())
                                                                .trackingUrl(event.trackingUrl())
                                                                .build())
                                                .toList())
                                .build();
        }

        private void appendSampleEvents(List<TrackingEvent> events, List<SampleEntity> samples,
                        Map<UUID, TestCatalogEntity> testMap) {
                for (SampleEntity sample : samples) {
                        String testName = testName(sample, testMap);
                        String barcode = sample.getBarcode();
                        boolean recollectionRequested = sample.getStatus() == SampleStatus.RECOLLECTION_REQUIRED
                                        || sample.getParentSample() != null;
                        events.add(new TrackingEvent(
                                        "COLLECTION",
                                        recollectionRequested ? "Re-collection requested" : "Sample prepared",
                                        recollectionRequested
                                                        ? testName + " sample " + barcode
                                                                        + " is waiting for re-collection after rejection."
                                                        : testName + " sample " + barcode + " is waiting for collection.",
                                        sample.getCollectedAt() == null ? "CURRENT" : "COMPLETED",
                                        instantText(sample.getCreatedAt()),
                                        sample.getCreatedBy(),
                                        testName,
                                        barcode,
                                        null,
                                        null,
                                        null));

                        if (sample.getCollectedAt() != null) {
                                events.add(new TrackingEvent(
                                                "COLLECTION",
                                                "Sample collected",
                                                testName + " sample " + barcode + " collected and sent to lab reception.",
                                                "COMPLETED",
                                                instantText(sample.getCollectedAt()),
                                                sample.getCollectedBy(),
                                                testName,
                                                barcode,
                                                null,
                                                null,
                                                null));
                        }

                        if (sample.getRejectedAt() != null) {
                                String reason = sample.getRejectionReason() == null
                                                ? sample.getRejectionNotes()
                                                : sample.getRejectionReason().name().replace('_', ' ');
                                events.add(new TrackingEvent(
                                                "COLLECTION",
                                                "Sample rejected",
                                                trimDescription("Sample " + barcode + " was rejected. " + nullToEmpty(reason)),
                                                "FAILED",
                                                instantText(sample.getRejectedAt()),
                                                sample.getRejectedBy(),
                                                testName,
                                                barcode,
                                                null,
                                                null,
                                                null));
                        }
                }
        }

        private void appendMltEvents(List<TrackingEvent> events, List<SampleEntity> samples,
                        Map<UUID, List<TestResultEntity>> resultsBySampleId,
                        Map<UUID, TestCatalogEntity> testMap) {
                for (SampleEntity sample : samples) {
                        String testName = testName(sample, testMap);
                        if (sample.getStatus() == SampleStatus.IN_TESTING) {
                                events.add(new TrackingEvent(
                                                "MLT",
                                                "Currently at MLT",
                                                testName + " is being processed by MLT.",
                                                "CURRENT",
                                                instantText(sample.getLastModifiedAt()),
                                                sample.getLastModifiedBy(),
                                                testName,
                                                sample.getBarcode(),
                                                null,
                                                null,
                                                null));
                        }

                        List<TestResultEntity> sampleResults = resultsBySampleId.getOrDefault(sample.getId(), List.of());
                        sampleResults.stream()
                                        .filter(result -> !Boolean.TRUE.equals(result.getDraft()))
                                        .filter(result -> result.getStatus() != null)
                                        .min(Comparator.comparing(result -> firstPresent(
                                                        result.getCreatedAt(),
                                                        result.getLastModifiedAt()),
                                                        Comparator.nullsLast(Comparator.naturalOrder())))
                                        .ifPresent(result -> events.add(new TrackingEvent(
                                                        "MLT",
                                                        result.getStatus() == ResultStatus.RETURNED_FOR_RECHECK
                                                                        ? "Result re-entered by MLT"
                                                                        : "Result entered by MLT",
                                                        testName + " result submitted for supervisor verification.",
                                                        "COMPLETED",
                                                        instantText(firstPresent(result.getLastModifiedAt(),
                                                                        result.getCreatedAt())),
                                                        firstPresent(result.getLastModifiedBy(), result.getCreatedBy()),
                                                        testName,
                                                        sample.getBarcode(),
                                                        null,
                                                        null,
                                                        null)));
                }
        }

        private void appendAuditEvents(List<TrackingEvent> events, Collection<UUID> entityIds, String entityType,
                        List<String> actions, Map<UUID, TestCatalogEntity> testMap, List<SampleEntity> samples) {
                if (entityIds.isEmpty()) {
                        return;
                }

                Map<UUID, SampleEntity> samplesById = samples.stream()
                                .collect(Collectors.toMap(SampleEntity::getId, Function.identity()));
                for (AuditLog log : auditLogRepository.findByEntityTypeAndEntityIdInAndActionInOrderByTimestampAsc(
                                entityType, entityIds, actions)) {
                        SampleEntity sample = samplesById.get(log.getEntityId());
                        if (sample == null) {
                                continue;
                        }
                        String testName = testName(sample, testMap);
                        boolean accepted = "ACCEPTED".equals(log.getAction());
                        events.add(new TrackingEvent(
                                        "RECEPTION",
                                        accepted ? "Accepted at lab reception" : "Rejected at lab reception",
                                        accepted
                                                        ? testName + " sample " + sample.getBarcode() + " accepted for MLT."
                                                        : testName + " sample " + sample.getBarcode()
                                                                        + " rejected at reception and returned for correction.",
                                        accepted ? "COMPLETED" : "FAILED",
                                        localDateTimeText(log.getTimestamp()),
                                        log.getPerformedBy(),
                                        testName,
                                        sample.getBarcode(),
                                        null,
                                        null,
                                        null));
                }
        }

        private void appendVerificationEvents(List<TrackingEvent> events, List<TestResultEntity> results,
                        Map<UUID, TestCatalogEntity> testMap) {
                if (results.isEmpty()) {
                        return;
                }

                List<UUID> resultIds = results.stream().map(TestResultEntity::getId).toList();
                Set<String> auditedSampleActions = new HashSet<>();
                for (AuditLog log : auditLogRepository.findByEntityTypeAndEntityIdInAndActionInOrderByTimestampAsc(
                                "VERIFICATION",
                                resultIds,
                                List.of("VERIFICATION_APPROVED", "VERIFICATION_RETURNED_TO_MLT",
                                                "VERIFICATION_RETURNED_FROM_CLINICAL", "CLINICAL_AUTHORIZED"))) {
                        TestResultEntity result = results.stream()
                                        .filter(candidate -> candidate.getId().equals(log.getEntityId()))
                                        .findFirst()
                                        .orElse(null);
                        if (result == null) {
                                continue;
                        }

                        String timestamp = localDateTimeText(log.getTimestamp());
                        auditedSampleActions.add(log.getAction() + ":" + result.getSample().getId());
                        events.add(toVerificationAuditEvent(log, result, timestamp, testMap));
                }

                Set<String> emittedFallbackSampleActions = new HashSet<>();
                for (TestResultEntity result : results) {
                        String testName = testName(result.getSample(), testMap);
                        UUID sampleId = result.getSample().getId();
                        if (result.getTechnicallyVerifiedAt() != null
                                        && !auditedSampleActions.contains("VERIFICATION_APPROVED:" + sampleId)
                                        && emittedFallbackSampleActions.add("VERIFICATION_APPROVED:" + sampleId)) {
                                events.add(new TrackingEvent(
                                                "SUPERVISOR",
                                                "Verified by lab supervisor",
                                                testName + " result technically verified.",
                                                "COMPLETED",
                                                instantText(result.getTechnicallyVerifiedAt()),
                                                result.getTechnicallyVerifiedBy(),
                                                testName,
                                                result.getSample().getBarcode(),
                                                null,
                                                null,
                                                null));
                        }
                        if (result.getClinicallyAuthorizedAt() != null
                                        && !auditedSampleActions.contains("CLINICAL_AUTHORIZED:" + sampleId)
                                        && emittedFallbackSampleActions.add("CLINICAL_AUTHORIZED:" + sampleId)) {
                                events.add(new TrackingEvent(
                                                "PATHOLOGIST",
                                                "Authorized by pathologist",
                                                testName + " report authorized and sent to dispatch.",
                                                "COMPLETED",
                                                instantText(result.getClinicallyAuthorizedAt()),
                                                result.getClinicallyAuthorizedBy(),
                                                testName,
                                                result.getSample().getBarcode(),
                                                null,
                                                null,
                                                null));
                        }
                        if (result.getReturnedAt() != null
                                        && emittedFallbackSampleActions.add("RETURNED:" + sampleId)) {
                                events.add(new TrackingEvent(
                                                "PATHOLOGIST",
                                                "Returned to MLT by pathologist",
                                                trimDescription(testName + " returned for correction. "
                                                                + nullToEmpty(result.getReturnReason())),
                                                "FAILED",
                                                instantText(result.getReturnedAt()),
                                                result.getReturnedBy(),
                                                testName,
                                                result.getSample().getBarcode(),
                                                null,
                                                null,
                                                null));
                        }
                }
        }

        private TrackingEvent toVerificationAuditEvent(AuditLog log, TestResultEntity result, String timestamp,
                        Map<UUID, TestCatalogEntity> testMap) {
                String testName = testName(result.getSample(), testMap);
                String action = log.getAction();
                if ("VERIFICATION_APPROVED".equals(action)) {
                        return new TrackingEvent("SUPERVISOR", "Verified by lab supervisor",
                                        testName + " result technically verified.", "COMPLETED", timestamp,
                                        log.getPerformedBy(), testName, result.getSample().getBarcode(), null, null, null);
                }
                if ("VERIFICATION_RETURNED_TO_MLT".equals(action)) {
                        return new TrackingEvent("SUPERVISOR", "Returned to MLT by supervisor",
                                        testName + " result returned for correction and re-entry.", "FAILED", timestamp,
                                        log.getPerformedBy(), testName, result.getSample().getBarcode(), null, null, null);
                }
                if ("VERIFICATION_RETURNED_FROM_CLINICAL".equals(action)) {
                        return new TrackingEvent("PATHOLOGIST", "Returned to MLT by pathologist",
                                        testName + " report returned for correction.", "FAILED", timestamp,
                                        log.getPerformedBy(), testName, result.getSample().getBarcode(), null, null, null);
                }
                return new TrackingEvent("PATHOLOGIST", "Authorized by pathologist",
                                testName + " report authorized and sent to dispatch.", "COMPLETED", timestamp,
                                log.getPerformedBy(), testName, result.getSample().getBarcode(), null, null, null);
        }

        private void appendDispatchEvents(List<TrackingEvent> events, List<TestResultEntity> results) {
                List<String> reportReferences = results.stream()
                                .map(result -> result.getId().toString())
                                .distinct()
                                .toList();
                if (reportReferences.isEmpty()) {
                        return;
                }

                for (ReportDispatchItemEntity item : dispatchItemRepository.findByReportReferenceIn(reportReferences)) {
                        events.add(new TrackingEvent(
                                        "DISPATCH",
                                        "Report ready for dispatch",
                                        item.getTestPanelLabel() + " report is waiting in report dispatch.",
                                        item.getOverallStatus() == DispatchItemStatus.PENDING ? "CURRENT" : "COMPLETED",
                                        localDateTimeText(item.getAuthorizedAt()),
                                        item.getCreatedBy(),
                                        item.getTestPanelLabel(),
                                        null,
                                        null,
                                        null,
                                        null));

                        List<ReportDeliveryAttemptEntity> attempts = deliveryAttemptRepository
                                        .findByDispatchItemIdOrderByCreatedAtAsc(item.getId());
                        for (ReportDeliveryAttemptEntity attempt : attempts) {
                                String method = methodLabel(attempt);
                                if (attempt.getDispatchedAt() != null) {
                                        events.add(new TrackingEvent(
                                                        "DISPATCH",
                                                        "Dispatched via " + method,
                                                        deliveryDescription(item, attempt, "sent"),
                                                        attempt.getDeliveredAt() == null
                                                                        && attempt.getStatus() != DeliveryAttemptStatus.FAILED
                                                                                        ? "CURRENT"
                                                                                        : "COMPLETED",
                                                        localDateTimeText(attempt.getDispatchedAt()),
                                                        attempt.getCreatedBy(),
                                                        item.getTestPanelLabel(),
                                                        null,
                                                        method,
                                                        attempt.getTrackingNumber(),
                                                        attempt.getTrackingUrl()));
                                }
                                if (attempt.getStatus() == DeliveryAttemptStatus.FAILED) {
                                        events.add(new TrackingEvent(
                                                        "DISPATCH",
                                                        "Delivery failed via " + method,
                                                        trimDescription(nullToEmpty(attempt.getFailureReason())),
                                                        "FAILED",
                                                        instantText(firstPresent(attempt.getLastModifiedAt(),
                                                                        attempt.getCreatedAt())),
                                                        attempt.getLastModifiedBy(),
                                                        item.getTestPanelLabel(),
                                                        null,
                                                        method,
                                                        attempt.getTrackingNumber(),
                                                        attempt.getTrackingUrl()));
                                }
                                if (attempt.getDeliveredAt() != null) {
                                        events.add(new TrackingEvent(
                                                        "DELIVERED",
                                                        "Delivered via " + method,
                                                        deliveryDescription(item, attempt, "delivered"),
                                                        "COMPLETED",
                                                        localDateTimeText(attempt.getDeliveredAt()),
                                                        firstPresent(attempt.getLastModifiedBy(), attempt.getCreatedBy()),
                                                        item.getTestPanelLabel(),
                                                        null,
                                                        method,
                                                        attempt.getTrackingNumber(),
                                                        attempt.getTrackingUrl()));
                                }
                        }
                }
        }

        private List<OrderTrackingStepResponse> buildTrackingSteps(List<TrackingEvent> events,
                        List<TrackingEvent> sortedEvents,
                        OrderStatus orderStatus) {
                List<String> order = List.of("ORDER", "COLLECTION", "RECEPTION", "MLT", "SUPERVISOR", "PATHOLOGIST",
                                "DISPATCH", "DELIVERED");
                Map<String, String> labels = Map.of(
                                "ORDER", "Order",
                                "COLLECTION", "Sample collection",
                                "RECEPTION", "Lab reception",
                                "MLT", "MLT",
                                "SUPERVISOR", "Supervisor",
                                "PATHOLOGIST", "Pathologist",
                                "DISPATCH", "Dispatch",
                                "DELIVERED", "Delivered");
                Set<String> completedStages = events.stream()
                                .filter(event -> "COMPLETED".equals(event.status()))
                                .map(TrackingEvent::stage)
                                .collect(Collectors.toSet());
                Set<String> failedStages = events.stream()
                                .filter(event -> "FAILED".equals(event.status()))
                                .map(TrackingEvent::stage)
                                .collect(Collectors.toSet());
                TrackingEvent latest = sortedEvents.stream().findFirst().orElse(null);
                String currentStage = sortedEvents.stream()
                                .filter(event -> "CURRENT".equals(event.status()))
                                .findFirst()
                                .map(TrackingEvent::stage)
                                .orElseGet(() -> {
                                        if (latest != null && "FAILED".equals(latest.status())) {
                                                return latest.stage();
                                        }
                                        return orderStatus == OrderStatus.COMPLETED ? "DELIVERED"
                                                        : nextStage(order, completedStages);
                                });

                return order.stream()
                                .map(key -> {
                                        String status;
                                        if (orderStatus == OrderStatus.CANCELLED) {
                                                status = "PENDING";
                                        } else if ("DELIVERED".equals(key) && orderStatus == OrderStatus.COMPLETED) {
                                                status = "COMPLETED";
                                        } else if (failedStages.contains(key) && key.equals(currentStage)) {
                                                status = "FAILED";
                                        } else if (key.equals(currentStage) && !completedStages.contains(key)) {
                                                status = "CURRENT";
                                        } else if (completedStages.contains(key)) {
                                                status = "COMPLETED";
                                        } else {
                                                status = "PENDING";
                                        }

                                        String timestamp = events.stream()
                                                        .filter(event -> key.equals(event.stage()))
                                                        .map(TrackingEvent::timestamp)
                                                        .filter(Objects::nonNull)
                                                        .max(String::compareTo)
                                                        .orElse(null);

                                        return OrderTrackingStepResponse.builder()
                                                        .key(key)
                                                        .label(labels.get(key))
                                                        .status(status)
                                                        .timestamp(timestamp)
                                                        .description(stepDescription(key, status))
                                                        .build();
                                })
                                .toList();
        }

        private String nextStage(List<String> order, Set<String> completedStages) {
                for (String stage : order) {
                        if (!completedStages.contains(stage)) {
                                return stage;
                        }
                }
                return "DELIVERED";
        }

        private String stepDescription(String key, String status) {
                if ("COMPLETED".equals(status)) {
                        return "Completed";
                }
                if ("FAILED".equals(status)) {
                        return "Returned or rejected";
                }
                if ("CURRENT".equals(status)) {
                        return "Current stage";
                }
                return "Waiting";
        }

        private void reconcileOrderCompletionFromDispatch(OrderEntity order) {
                if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.COMPLETED) {
                        return;
                }

                boolean changed = false;
                for (OrderItemEntity item : order.getItems()) {
                        if (item.isDeleted()) {
                                continue;
                        }

                        SampleEntity activeSample = latestActiveSample(item);
                        if (activeSample == null) {
                                return;
                        }

                        if (activeSample.getStatus() == SampleStatus.DISPATCHED) {
                                if (item.getStatus() != SampleStatus.DISPATCHED) {
                                        item.setStatus(SampleStatus.DISPATCHED);
                                        changed = true;
                                }
                                continue;
                        }

                        List<String> reportReferences = testResultRepository.findBySampleId(activeSample.getId())
                                        .stream()
                                        .filter(result -> !result.isDeleted())
                                        .map(result -> result.getId().toString())
                                        .toList();

                        if (!reportReferences.isEmpty()
                                        && dispatchItemRepository.existsByReportReferenceInAndOverallStatus(
                                                        reportReferences,
                                                        DispatchItemStatus.DELIVERED)) {
                                activeSample.setStatus(SampleStatus.DISPATCHED);
                                item.setStatus(SampleStatus.DISPATCHED);
                                sampleRepository.save(activeSample);
                                changed = true;
                        }
                }

                boolean allItemsDispatched = order.getItems().stream()
                                .filter(item -> !item.isDeleted())
                                .allMatch(item -> {
                                        SampleEntity activeSample = latestActiveSample(item);
                                        return activeSample != null && activeSample.getStatus() == SampleStatus.DISPATCHED;
                                });

                if (allItemsDispatched) {
                        order.setStatus(OrderStatus.COMPLETED);
                        changed = true;
                }

                if (changed) {
                        orderRepository.save(order);
                }
        }

        private SampleEntity latestActiveSample(OrderItemEntity item) {
                return item.getSamples().stream()
                                .filter(sample -> !sample.isDeleted())
                                .filter(sample -> sample.getStatus() != SampleStatus.REJECTED)
                                .max(Comparator.comparing(SampleEntity::getCreatedAt,
                                                Comparator.nullsLast(Comparator.naturalOrder())))
                                .orElse(null);
        }

        private String testName(SampleEntity sample, Map<UUID, TestCatalogEntity> testMap) {
                TestCatalogEntity catalog = testMap.get(sample.getOrderItem().getTestId());
                return catalog == null ? "Test" : catalog.getTestName();
        }

        private String methodLabel(ReportDeliveryAttemptEntity attempt) {
                return attempt.getMethod() == null ? "UNKNOWN" : attempt.getMethod().name().replace('_', ' ');
        }

        private String deliveryDescription(ReportDispatchItemEntity item, ReportDeliveryAttemptEntity attempt,
                        String verb) {
                String contact = attempt.getRecipientContact() == null || attempt.getRecipientContact().isBlank()
                                ? null
                                : " to " + attempt.getRecipientContact();
                String tracking = attempt.getTrackingNumber() == null || attempt.getTrackingNumber().isBlank()
                                ? null
                                : " Tracking: " + attempt.getTrackingNumber() + ".";
                return trimDescription(item.getTestPanelLabel() + " report " + verb + " via " + methodLabel(attempt)
                                + nullToEmpty(contact) + "." + nullToEmpty(tracking));
        }

        private String instantText(Instant value) {
                return value == null ? null : value.toString();
        }

        private String localDateTimeText(LocalDateTime value) {
                // These LocalDateTime columns hold UTC wall-time (written by
                // LocalDateTime.now() on the UTC app container), so serialize with the
                // Z marker like the Instant fields — otherwise clients render raw UTC.
                return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
        }

        private String nullToEmpty(String value) {
                return value == null ? "" : value;
        }

        private String trimDescription(String value) {
                if (value == null || value.isBlank()) {
                        return null;
                }
                return value.trim().replaceAll("\\s+", " ");
        }

        private <T> T firstPresent(T first, T second) {
                return first != null ? first : second;
        }

        private record TrackingEvent(
                        String stage,
                        String title,
                        String description,
                        String status,
                        String timestamp,
                        String performedBy,
                        String testName,
                        String barcode,
                        String method,
                        String trackingNumber,
                        String trackingUrl) {
        }

        /**
         * WHY: Only PENDING orders can be cancelled — cancelling an IN_PROGRESS order
         * would abandon samples already in transit to the laboratory, violating chain
         * of custody.
         *
         * @param id the internal UUID of the order to cancel
         * @return the updated order as a response DTO
         * @throws ResourceNotFoundException       if the order does not exist
         * @throws InvalidStateTransitionException if the order is not in PENDING state
         */
        public OrderResponse cancelOrder(UUID id) {
                OrderEntity order = orderRepository.findByIdAndDeletedFalse(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + id));

                // Tenant isolation. The order lists are branch-scoped but this loads
                // by id, so without the guard a user could cancel another branch's
                // pending order — cascading its samples out of that branch's
                // phlebotomy queue.
                SecurityUtils.assertCanAccessBranch(order.getBranchCode(), "Order", id);

                if (order.getStatus() != OrderStatus.PENDING) {
                        throw new InvalidStateTransitionException(
                                        "Cannot cancel order " + order.getOrderNo() +
                                                        " — only PENDING orders can be cancelled, current status is: "
                                                        + order.getStatus());
                }

                if (order.getBill() != null && order.getBill().getPaymentStatus() == PaymentStatus.PAID) {
                        throw new InvalidStateTransitionException(
                                        "Cannot cancel order " + order.getOrderNo() +
                                                        " — the bill has already been PAID. Please contact accounts for a refund if necessary.");
                }

                order.setStatus(OrderStatus.CANCELLED);
                OrderEntity saved = orderRepository.save(order);
                log.info("Order {} cancelled by {}", saved.getOrderNo(), SecurityUtils.getCurrentUsername());
                return toResponse(saved, null);
        }

        /**
         * WHY: All entity-to-DTO mapping is centralised here. The testMap parameter
         * allows
         * item lines to be enriched with testCode/testName from the catalog. When
         * testMap is
         * null (read-only paths where catalog was not pre-fetched) only price and
         * testId are mapped —
         * keeping read paths fast without a second catalog query.
         *
         * @param order   the OrderEntity to convert
         * @param testMap optional map of UUID → TestCatalogEntity for item enrichment,
         *                may be null
         * @return the OrderResponse DTO safe for exposure outside the service layer
         */
        private OrderResponse toResponse(OrderEntity order, Map<UUID, TestCatalogEntity> testMap) {
                // Get patient details — graceful null if unavailable
                PatientResponse patient = patientClientService
                                .getPatientByCode(order.getPatientId(), SecurityUtils.getCurrentBearerToken());

                List<OrderItemResponse> itemResponses = order.getItems().stream()
                                .map(item -> {
                                        TestCatalogEntity test = testMap != null ? testMap.get(item.getTestId()) : null;

                                        // WHY: A rejected sample triggers a new collection, so an item can have
                                        // multiple sample records.
                                        // We only display the latest active (non-rejected) sample barcode and status to
                                        // the user.
                                        SampleEntity activeSample = item.getSamples().stream()
                                                        .filter(s -> s.getStatus() != SampleStatus.REJECTED)
                                                        .max(Comparator.comparing(SampleEntity::getCreatedAt))
                                                        .orElse(null);

                                        return OrderItemResponse.builder()
                                                        .testId(item.getTestId().toString())
                                                        .testCode(test != null ? test.getTestCode() : null)
                                                        .testName(test != null ? test.getTestName() : null)
                                                        .category(test != null ? test.getCategory() : null)
                                                        .price(item.getPrice())
                                                        .sampleBarcode(activeSample != null ? activeSample.getBarcode()
                                                                        : null)
                                                        .sampleStatus(activeSample != null ? activeSample.getStatus()
                                                                        : null)
                                                        .build();
                                })
                                .toList();

                return OrderResponse.builder()
                                .id(order.getId())
                                .orderId(order.getOrderNo())
                                .patientId(order.getPatientId())
                                .patientName(patient != null ? patient.getFullName() : null)
                                .patientAge(patient != null && patient.getDob() != null
                                                ? Period.between(patient.getDob(), LocalDate.now()).getYears()
                                                : null)
                                .patientGender(patient != null && patient.getGender() != null
                                                ? patient.getGender().name()
                                                : null)
                                .orderDate(order.getCreatedAt() != null ? order.getCreatedAt().toString() : null)
                                .status(order.getStatus())
                                .priority(order.getPriority())
                                .referringDoctor(order.getReferringDoctor())
                                .referringDepartment(order.getReferringDepartment())
                                .remarks(order.getRemarks())
                                .createdBy(order.getCreatedBy())
                                .paymentStatus(order.getBill() != null ? order.getBill().getPaymentStatus() : null)
                                .tests(itemResponses)
                                .build();
        }
}
