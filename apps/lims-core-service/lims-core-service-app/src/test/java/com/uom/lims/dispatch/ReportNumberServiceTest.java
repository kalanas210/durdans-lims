package com.uom.lims.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportNumberServiceTest {

    @Test
    void formatsTheSameShapeAsThePatientCodeAndCaseNumber() {
        assertThat(ReportNumberService.format(2026, 42)).isEqualTo("REP2026-00042");
        assertThat(ReportNumberService.format(2026, 1)).isEqualTo("REP2026-00001");
    }

    /** A busy year must widen rather than wrap back onto a number already issued. */
    @Test
    void widensPastFiveDigitsInsteadOfWrapping() {
        assertThat(ReportNumberService.format(2026, 123_456)).isEqualTo("REP2026-123456");
    }

    @Test
    void issuesANumberOnFirstUse() {
        ReportDispatchItemRepository repository = mock(ReportDispatchItemRepository.class);
        when(repository.getNextReportSequence()).thenReturn(7L);
        ReportDispatchItemEntity item = new ReportDispatchItemEntity();

        String issued = new ReportNumberService(repository).ensureReportNo(item);

        assertThat(issued).endsWith("-00007").startsWith("REP");
        assertThat(item.getReportNo()).isEqualTo(issued);
    }

    /**
     * Re-authorizing or re-dispatching must not renumber: the patient is holding a
     * copy with the first number printed on it.
     */
    @Test
    void neverReissuesANumberAlreadyPrinted() {
        ReportDispatchItemRepository repository = mock(ReportDispatchItemRepository.class);
        ReportDispatchItemEntity item = new ReportDispatchItemEntity();
        item.setReportNo("REP2026-00042");

        String kept = new ReportNumberService(repository).ensureReportNo(item);

        assertThat(kept).isEqualTo("REP2026-00042");
        verify(repository, never()).getNextReportSequence();
    }
}
