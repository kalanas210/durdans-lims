package com.uom.lims.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uom.lims.api.dispatch.dto.request.RegisterAuthorizedReportRequest;
import com.uom.lims.api.dispatch.enums.DispatchItemStatus;
import com.uom.lims.api.dispatch.enums.DeliveryAttemptStatus;
import com.uom.lims.api.dispatch.enums.DeliveryMethod;
import com.uom.lims.api.verification.enums.ResultStatus;
import com.uom.lims.audit.AuditService;
import com.uom.lims.entity.OrderEntity;
import com.uom.lims.entity.OrderItemEntity;
import com.uom.lims.entity.SampleEntity;
import com.uom.lims.entity.TestResultEntity;
import com.uom.lims.exception.InvalidRequestException;
import com.uom.lims.exception.ResourceNotFoundException;
import com.uom.lims.repository.TestResultRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * registerAuthorizedReport is the caller-driven entry into dispatch. It upserts
 * on (reportReference, branchCode), so anything it accepts without checking can
 * be used to put a report in front of a patient — the dispatch email renders
 * artifactUri as a "Download report" link sent from the hospital's own address.
 *
 * <p>These tests pin the three things it must verify: the reference names a real
 * result, that result carries a pathologist's authorization, and it belongs to
 * the branch being registered against.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchServiceRegisterTest {

    private static final String BRANCH = "BR001";

    @Mock
    private ReportDispatchItemRepository itemRepository;

    @Mock
    private ReportNumberService reportNumberService;

    @Mock
    private ReportDeliveryAttemptRepository attemptRepository;

    @Mock
    private ReportDispatchChannelService channelService;

    @Mock
    private AuditService auditService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TestResultRepository testResultRepository;

    @InjectMocks
    private DispatchService dispatchService;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateSuperAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }

    /** A result reachable through sample -> orderItem -> order, which is where its branch lives. */
    private TestResultEntity resultIn(String branchCode, ResultStatus status) {
        OrderEntity order = new OrderEntity();
        order.setBranchCode(branchCode);

        OrderItemEntity orderItem = new OrderItemEntity();
        orderItem.setOrder(order);

        SampleEntity sample = new SampleEntity();
        sample.setOrderItem(orderItem);

        TestResultEntity result = new TestResultEntity();
        result.setSample(sample);
        result.setStatus(status);
        return result;
    }

    @Test
    void register_rejectsNonSuperAdmin_whenBranchMissingFromToken() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "mlt", "n/a", List.of(new SimpleGrantedAuthority("ROLE_MLT"))));

        RegisterAuthorizedReportRequest req = RegisterAuthorizedReportRequest.builder()
                .reportReference(UUID.randomUUID().toString())
                .branchCode(BRANCH)
                .patientDisplayName("X")
                .testPanelLabel("Y")
                .build();

        assertThrows(InvalidRequestException.class,
                () -> dispatchService.registerAuthorizedReport(req, "192.168.1.10"));
    }

    @Test
    void finalizeDispatchReloadsAttemptsInsteadOfTouchingDetachedLazyCollection() {
        UUID itemId = UUID.randomUUID();
        ReportDispatchItemEntity detached = new ReportDispatchItemEntity();
        detached.setId(itemId);

        ReportDispatchItemEntity managed = new ReportDispatchItemEntity();
        managed.setId(itemId);
        managed.setReportReference(UUID.randomUUID().toString());
        managed.setBranchCode(BRANCH);
        managed.setPatientDisplayName("Patient");
        managed.setTestPanelLabel("FBC");
        managed.setAuthorizedAt(LocalDateTime.now());
        managed.setOverallStatus(DispatchItemStatus.PENDING);

        ReportDeliveryAttemptEntity completed = new ReportDeliveryAttemptEntity();
        completed.setDispatchItem(detached);
        completed.setMethod(DeliveryMethod.EMAIL);
        completed.setStatus(DeliveryAttemptStatus.DELIVERED);
        ReportDeliveryAttemptEntity completedSms = new ReportDeliveryAttemptEntity();
        completedSms.setDispatchItem(detached);
        completedSms.setMethod(DeliveryMethod.SMS);
        completedSms.setStatus(DeliveryAttemptStatus.DELIVERED);
        ReportDeliveryAttemptEntity historicalPendingSms = new ReportDeliveryAttemptEntity();
        historicalPendingSms.setDispatchItem(detached);
        historicalPendingSms.setMethod(DeliveryMethod.SMS);
        historicalPendingSms.setStatus(DeliveryAttemptStatus.PENDING);
        ReportDeliveryAttemptEntity historicalWhatsapp = new ReportDeliveryAttemptEntity();
        historicalWhatsapp.setDispatchItem(detached);
        historicalWhatsapp.setMethod(DeliveryMethod.WHATSAPP);
        historicalWhatsapp.setStatus(DeliveryAttemptStatus.PENDING);

        when(itemRepository.findById(itemId)).thenReturn(Optional.of(managed));
        when(itemRepository.save(managed)).thenReturn(managed);
        when(attemptRepository.findByDispatchItemIdOrderByCreatedAtAsc(itemId))
                .thenReturn(List.of(historicalPendingSms, historicalWhatsapp, completed, completedSms));
        when(testResultRepository.findById(any())).thenReturn(Optional.empty());

        var response = dispatchService.finalizeDispatch(
                detached, List.of(completed, completedSms), "DISPATCH_REPORT", "DISPATCH_EXECUTED", "{}", "127.0.0.1");

        assertEquals(DispatchItemStatus.DELIVERED, response.getOverallStatus());
        verify(attemptRepository).saveAll(List.of(completed, completedSms));
    }

    @Test
    void register_updatesExistingRow_whenResultIsAuthorizedInSameBranch() throws Exception {
        authenticateSuperAdmin();
        UUID resultId = UUID.randomUUID();

        // A corrected report legitimately re-registers under the same reference,
        // so updating an already-DELIVERED row is allowed — but only because the
        // result behind it is authorized and in this branch.
        when(testResultRepository.findById(resultId))
                .thenReturn(Optional.of(resultIn(BRANCH, ResultStatus.CLINICALLY_AUTHORIZED)));

        ReportDispatchItemEntity existing = new ReportDispatchItemEntity();
        existing.setId(UUID.randomUUID());
        existing.setReportReference(resultId.toString());
        existing.setBranchCode(BRANCH);
        existing.setPatientDisplayName("Old");
        existing.setTestPanelLabel("OldTest");
        existing.setAuthorizedAt(LocalDateTime.now().minusDays(1));
        existing.setOverallStatus(DispatchItemStatus.DELIVERED);

        when(itemRepository.findByReportReferenceAndBranchCode(resultId.toString(), BRANCH))
                .thenReturn(Optional.of(existing));
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");
        when(itemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterAuthorizedReportRequest req = RegisterAuthorizedReportRequest.builder()
                .reportReference(resultId.toString())
                .branchCode(BRANCH)
                .patientDisplayName("New Name")
                .testPanelLabel("New Panel")
                .build();

        dispatchService.registerAuthorizedReport(req, "127.0.0.1");

        ArgumentCaptor<ReportDispatchItemEntity> captor = ArgumentCaptor.forClass(ReportDispatchItemEntity.class);
        verify(itemRepository, times(1)).save(captor.capture());
        assertEquals("New Name", captor.getValue().getPatientDisplayName());
        assertEquals("New Panel", captor.getValue().getTestPanelLabel());
        assertEquals(DispatchItemStatus.DELIVERED, captor.getValue().getOverallStatus());
    }

    @Test
    void register_rejects_whenResultIsNotClinicallyAuthorized() {
        authenticateSuperAdmin();
        UUID resultId = UUID.randomUUID();
        when(testResultRepository.findById(resultId))
                .thenReturn(Optional.of(resultIn(BRANCH, ResultStatus.TECHNICALLY_VERIFIED)));

        RegisterAuthorizedReportRequest req = RegisterAuthorizedReportRequest.builder()
                .reportReference(resultId.toString())
                .branchCode(BRANCH)
                .patientDisplayName("Patient")
                .testPanelLabel("FBC")
                .build();

        assertThrows(InvalidRequestException.class,
                () -> dispatchService.registerAuthorizedReport(req, "127.0.0.1"));
        verify(itemRepository, never()).save(any());
    }

    @Test
    void register_rejects_whenResultBelongsToAnotherBranch() {
        authenticateSuperAdmin();
        UUID resultId = UUID.randomUUID();
        when(testResultRepository.findById(resultId))
                .thenReturn(Optional.of(resultIn("COL-1", ResultStatus.CLINICALLY_AUTHORIZED)));

        RegisterAuthorizedReportRequest req = RegisterAuthorizedReportRequest.builder()
                .reportReference(resultId.toString())
                .branchCode(BRANCH)
                .patientDisplayName("Patient")
                .testPanelLabel("FBC")
                .build();

        assertThrows(InvalidRequestException.class,
                () -> dispatchService.registerAuthorizedReport(req, "127.0.0.1"));
        verify(itemRepository, never()).save(any());
    }

    @Test
    void register_rejects_whenResultDoesNotExist() {
        authenticateSuperAdmin();
        UUID resultId = UUID.randomUUID();
        when(testResultRepository.findById(resultId)).thenReturn(Optional.empty());

        RegisterAuthorizedReportRequest req = RegisterAuthorizedReportRequest.builder()
                .reportReference(resultId.toString())
                .branchCode(BRANCH)
                .patientDisplayName("Patient")
                .testPanelLabel("FBC")
                .build();

        assertThrows(ResourceNotFoundException.class,
                () -> dispatchService.registerAuthorizedReport(req, "127.0.0.1"));
        verify(itemRepository, never()).save(any());
    }

    @Test
    void register_rejects_whenReferenceIsNotAResultId() {
        authenticateSuperAdmin();

        // The old contract accepted any string, so a caller could invent a
        // reference and land a fabricated report in the dispatch queue.
        RegisterAuthorizedReportRequest req = RegisterAuthorizedReportRequest.builder()
                .reportReference("REP-X")
                .branchCode(BRANCH)
                .patientDisplayName("Patient")
                .testPanelLabel("FBC")
                .build();

        assertThrows(InvalidRequestException.class,
                () -> dispatchService.registerAuthorizedReport(req, "127.0.0.1"));
        verify(itemRepository, never()).save(any());
    }
}
