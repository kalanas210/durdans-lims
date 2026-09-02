package com.uom.lims.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * The laboratory's own clock — the zone every timestamp is formatted in before a
 * person reads it, and the zone "today" is bounded by.
 *
 * <p>It exists because that zone used to be the JVM's, which is to say the
 * container's: the live host runs UTC and nothing set {@code TZ}, so the patient
 * list, the QC dashboard, lab operations and the monitoring feed all printed
 * times 5:30 behind the clock on the wall, and the day rolled over at 5:30 a.m.
 *
 * <p><b>Why this is a bean and not {@code TimeZone.setDefault}.</b> Moving the
 * JVM default would silently move storage with it. Timestamp columns carry no
 * zone, so Hibernate normalises {@code Instant} through whatever zone the JVM is
 * in — every row already written on the UTC host would read back 5:30 out.
 * Pinning {@code hibernate.jdbc.time_zone=UTC} to stop that then shifts
 * {@code LocalDateTime} columns instead, and the audit log's timestamp is one:
 * its hash chain is sealed by a Postgres trigger, so the shift makes every
 * sealed row fail verification as tampered with. Neither global switch is safe
 * while both mappings share a schema. Injecting the zone where a value is
 * formatted changes what is displayed and nothing else.
 */
@Slf4j
@Component
public class LabTimeZone {

    private final ZoneId zone;

    public LabTimeZone(@Value("${app.timezone:Asia/Colombo}") String timezone) {
        this.zone = ZoneId.of(timezone);
    }

    /** The zone to format in and to take "today" from. Never {@code systemDefault()}. */
    public ZoneId zone() {
        return zone;
    }

    /** Today's date in the laboratory, whatever date it is where the server runs. */
    public java.time.LocalDate today() {
        return java.time.LocalDate.now(zone);
    }

    @PostConstruct
    void announce() {
        ZoneId jvm = ZoneId.systemDefault();
        if (!jvm.equals(zone)) {
            log.info("Laboratory time zone is {}; this JVM runs in {}. Timestamps are formatted "
                    + "in the laboratory's zone and stored unchanged.", zone, jvm);
        }
    }
}
