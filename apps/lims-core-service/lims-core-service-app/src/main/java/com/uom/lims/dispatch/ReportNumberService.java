package com.uom.lims.dispatch;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Year;

/**
 * Issues the human-readable number a dispatched report is known by on the PDF,
 * in the patient's inbox and over the phone: {@code REP<year>-<5-digit
 * sequence>}, the same shape as the patient code ({@code PAT2026-00001}) and the
 * case number ({@code RES2026-00042}).
 *
 * <p>It does not replace {@code reportReference} — that stays the anchor result's
 * UUID, which is what the dispatch API is addressed by and what joins a report
 * back to the result it came from. This is the identifier for people, and the
 * two are kept apart deliberately: a patient reading a number off a printout
 * should not be reading a UUID, and a URL should not depend on a display format.
 *
 * <p>Issued once, on first registration, and never reissued — re-authorizing or
 * re-dispatching the same report keeps the number already printed on the copy
 * the patient is holding. The sequence is a Postgres sequence, so concurrent
 * authorizations cannot collide and a rolled-back transaction leaves a gap
 * rather than a duplicate.
 */
@Service
@RequiredArgsConstructor
public class ReportNumberService {

    private static final String PREFIX = "REP";

    private final ReportDispatchItemRepository itemRepository;

    /**
     * Assigns a report number to the item if it does not have one yet. The caller
     * owns the transaction and the save; this only mutates the managed entity.
     *
     * @return the item's report number (existing or newly issued)
     */
    public String ensureReportNo(ReportDispatchItemEntity item) {
        String existing = item.getReportNo();
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String reportNo = format(Year.now().getValue(), itemRepository.getNextReportSequence());
        item.setReportNo(reportNo);
        return reportNo;
    }

    /** {@code REP2026-00042}. Widens past five digits rather than wrapping. */
    static String format(int year, long sequence) {
        return PREFIX + year + "-" + String.format("%05d", sequence);
    }
}
