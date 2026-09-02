package com.uom.lims.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uom.lims.api.dto.response.InstrumentStatusResponse;
import com.uom.lims.api.dto.response.QcDashboardResponse;
import com.uom.lims.api.dto.response.QcRunItemResponse;
import com.uom.lims.instrument.InstrumentEntity;
import com.uom.lims.instrument.InstrumentRepository;
import com.uom.lims.qc.QcResultEntity;
import com.uom.lims.qc.QcResultRepository;
import com.uom.lims.repository.TestResultRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class LabOperationsService {

    // Zone bound at format time from the laboratory's own zone, not the container's.
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a");

    private final ObjectMapper objectMapper;
    private final InstrumentRepository instrumentRepository;
    private final TestResultRepository testResultRepository;
    private final QcResultRepository qcResultRepository;
    private final com.uom.lims.config.LabTimeZone labTimeZone;

    public LabOperationsService(
            ObjectMapper objectMapper,
            InstrumentRepository instrumentRepository,
            TestResultRepository testResultRepository,
            QcResultRepository qcResultRepository,
            com.uom.lims.config.LabTimeZone labTimeZone) {
        this.objectMapper = objectMapper;
        this.instrumentRepository = instrumentRepository;
        this.testResultRepository = testResultRepository;
        this.qcResultRepository = qcResultRepository;
        this.labTimeZone = labTimeZone;
    }

    public QcDashboardResponse getQcDashboard() {
        List<QcRunItemResponse> runs = loadQcRuns();

        int passed = (int) runs.stream().filter(run -> "PASS".equals(run.status())).count();
        int warnings = (int) runs.stream().filter(run -> "WARN".equals(run.status())).count();
        int failures = (int) runs.stream().filter(run -> "FAIL".equals(run.status())).count();

        return new QcDashboardResponse(
                runs.size(),
                passed,
                warnings,
                failures,
                runs);
    }

    public List<InstrumentStatusResponse> getInstruments() {
        List<InstrumentEntity> entities = instrumentRepository.findByActiveTrueOrderByNameAsc();
        if (entities.isEmpty()) {
            return loadReferenceData("reference-data/instruments.json", new TypeReference<>() {});
        }

        // "Today" is the laboratory's day. Taken from the container's clock it began
        // at 05:30 local, so the morning's work was counted against yesterday.
        Instant startOfDay = labTimeZone.today().atStartOfDay(labTimeZone.zone()).toInstant();

        return entities.stream()
                .filter(inst -> !"BENCH-MANUAL".equalsIgnoreCase(inst.getCode()))
                .map(inst -> {
                    String code = inst.getCode();
                    String name = inst.getName();
                    String type = inst.getInstrumentType() != null ? inst.getInstrumentType() : "Laboratory Analyser";
                    String model = extractModel(name, code);
                    String serial = extractSerial(code);
                    String location = extractLocation(type);

                    // 1. Live database count of test results today
                    int testsToday = (int) testResultRepository.countTestsSince(code, startOfDay);

                    // 2. Live QC status evaluated from database
                    String liveQc = resolveLiveQcStatus(code, "PASS");

                    // 3. Live Last Sync timestamp from real database activity
                    String lastSync = resolveLastSync(code);

                    // 4. Live Operational status
                    String status = resolveOperationalStatus(code, testsToday);

                    return new InstrumentStatusResponse(
                            code,
                            name,
                            type,
                            model,
                            serial,
                            status,
                            lastSync,
                            testsToday,
                            location,
                            liveQc
                    );
                }).toList();
    }

    private String extractModel(String name, String code) {
        if (name.contains("XN-1000")) return "XN-1000";
        if (name.contains("c501")) return "c501";
        if (name.contains("e411")) return "e411";
        if (name.contains("VITEK")) return "VITEK 2";
        return code.toUpperCase();
    }

    private String extractSerial(String code) {
        if ("inst-001".equalsIgnoreCase(code)) return "SYS-2021-4421";
        if ("inst-002".equalsIgnoreCase(code)) return "COB-2020-3312";
        if ("inst-003".equalsIgnoreCase(code)) return "VIT-2022-0091";
        if ("inst-004".equalsIgnoreCase(code)) return "COB-2019-5521";
        return "LAB-" + code.toUpperCase();
    }

    private String extractLocation(String type) {
        if (type == null) return "Central Laboratory";
        String lower = type.toLowerCase();
        if (lower.contains("haematology") || lower.contains("hematology")) {
            return "Haematology Lab - Bench 1";
        }
        if (lower.contains("chemistry") || lower.contains("biochemistry")) {
            return "Biochemistry Lab - Bench 2";
        }
        if (lower.contains("immunoassay") || lower.contains("immunology")) {
            return "Immunology Lab - Bench 3";
        }
        if (lower.contains("microbiology")) {
            return "Microbiology Lab - Bench 4";
        }
        return "Main Laboratory - Bench 1";
    }

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd MMM, hh:mm a");

    private String resolveLastSync(String code) {
        try {
            Instant latestTest = testResultRepository.findLatestActivity(code);
            Instant latestQc = qcResultRepository.findLatestQcActivity(code);

            Instant latest = null;
            if (latestTest != null && latestQc != null) {
                latest = latestTest.isAfter(latestQc) ? latestTest : latestQc;
            } else if (latestTest != null) {
                latest = latestTest;
            } else if (latestQc != null) {
                latest = latestQc;
            }

            if (latest == null) {
                return "Ready";
            }

            LocalDate today = labTimeZone.today();
            LocalDate activityDate = latest.atZone(labTimeZone.zone()).toLocalDate();

            long secondsAgo = Math.max(0, Duration.between(latest, Instant.now()).toSeconds());
            if (secondsAgo < 90) {
                return "Just now";
            }
            if (secondsAgo < 3600) {
                long mins = secondsAgo / 60;
                return mins + (mins == 1 ? " min ago" : " mins ago");
            }
            if (today.equals(activityDate)) {
                long hours = secondsAgo / 3600;
                if (hours < 12) {
                    return hours + (hours == 1 ? " hour ago" : " hours ago");
                }
                return "Today, " + TIME_FORMATTER.withZone(labTimeZone.zone()).format(latest);
            }
            return DATE_TIME_FORMATTER.withZone(labTimeZone.zone()).format(latest);
        } catch (Exception e) {
            return "Ready";
        }
    }

    private String resolveOperationalStatus(String code, int testsToday) {
        try {
            Instant latest = testResultRepository.findLatestActivity(code);
            if (latest != null && Duration.between(latest, Instant.now()).toMinutes() < 3) {
                return "busy";
            }
            return "online";
        } catch (Exception e) {
            return "online";
        }
    }

    private String resolveLiveQcStatus(String instrumentCode, String defaultStatus) {
        if (qcResultRepository == null || instrumentCode == null) {
            return defaultStatus != null ? defaultStatus : "PASS";
        }
        try {
            List<QcResultEntity> runs = qcResultRepository.findByOrderByPerformedAtDesc(PageRequest.of(0, 100))
                    .stream()
                    .filter(q -> instrumentCode.equalsIgnoreCase(q.getInstrument()))
                    .toList();

            if (runs.isEmpty()) {
                return defaultStatus != null ? defaultStatus : "PASS";
            }

            // Group by analyte + control level and pick ONLY the MOST RECENT run for each analyte
            java.util.Map<String, QcResultEntity> latestPerAnalyte = new java.util.LinkedHashMap<>();
            for (QcResultEntity run : runs) {
                String key = (run.getAnalyte() != null ? run.getAnalyte().trim().toLowerCase() : "default")
                        + "_" + (run.getControlLevel() != null ? run.getControlLevel().trim().toLowerCase() : "l1");
                latestPerAnalyte.putIfAbsent(key, run);
            }

            // If the latest run of any analyte is failing, instrument is FAIL
            boolean hasFail = latestPerAnalyte.values().stream().anyMatch(q -> "FAIL".equalsIgnoreCase(q.getStatus()));
            if (hasFail) {
                return "FAIL";
            }

            // If the latest run of any analyte has a warning, instrument is WARN
            boolean hasWarn = latestPerAnalyte.values().stream().anyMatch(q -> "WARN".equalsIgnoreCase(q.getStatus()));
            if (hasWarn) {
                return "WARN";
            }

            return "PASS";
        } catch (Exception e) {
            return defaultStatus != null ? defaultStatus : "PASS";
        }
    }

    public InstrumentStatusResponse syncInstrument(String instrumentId) {
        return getInstruments().stream()
                .filter(instrument -> instrument.id().equalsIgnoreCase(instrumentId))
                .findFirst()
                .map(instrument -> new InstrumentStatusResponse(
                        instrument.id(),
                        instrument.name(),
                        instrument.type(),
                        instrument.model(),
                        instrument.serial(),
                        "online",
                        "Just now",
                        instrument.testsToday(),
                        instrument.location(),
                        instrument.qcStatus()))
                .orElseThrow(() -> new IllegalArgumentException("Instrument not found: " + instrumentId));
    }

    private List<QcRunItemResponse> loadQcRuns() {
        Instant now = Instant.now();
        List<QcRunSeed> seeds = loadReferenceData("reference-data/qc-runs.json", new TypeReference<>() {});

        return seeds.stream()
                .map(seed -> new QcRunItemResponse(
                        seed.id(),
                        seed.instrument(),
                        seed.testGroup(),
                        seed.level(),
                        seed.result(),
                        seed.expected(),
                        seed.sd(),
                        seed.status(),
                        seed.performedBy(),
                        TIME_FORMATTER.withZone(labTimeZone.zone())
                                .format(now.minusSeconds(seed.minutesAgo() * 60L))))
                .toList();
    }

    private <T> List<T> loadReferenceData(String path, TypeReference<List<T>> typeReference) {
        try {
            return objectMapper.readValue(new ClassPathResource(path).getInputStream(), typeReference);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load reference data from " + path, exception);
        }
    }

    private record QcRunSeed(
            String id,
            String instrument,
            String testGroup,
            String level,
            String result,
            String expected,
            String sd,
            String status,
            String performedBy,
            int minutesAgo) {
    }
}
