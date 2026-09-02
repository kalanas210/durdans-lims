package com.uom.lims.notification;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * Swallows SMS instead of sending it. Correct for local development; a patient
 * safety problem anywhere else, because the caller cannot tell the difference —
 * requesting an OTP still returns 200 and the front desk still sees "code sent"
 * while nothing leaves the building.
 *
 * <p>That is exactly how it reached the live host: {@code SMS_PROVIDER} was
 * absent from the environment, the compose default selected {@code mock}, and
 * the only trace of a patient never receiving a verification code was one INFO
 * line among thousands. It now announces itself at startup, loudly enough to
 * find in a log tail, and louder still when the deployment profile says this is
 * not a developer's laptop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsService implements SmsService {

    /** Profiles on which a mock gateway means real patients get no codes. */
    private static final List<String> DEPLOYED_PROFILES = List.of("docker", "prod", "production");

    private final Environment environment;

    @PostConstruct
    void announce() {
        String banner = "SMS gateway is MOCKED — no verification code, bill alert or report "
                + "notification will actually be delivered. Set SMS_PROVIDER=ozonedesk with "
                + "SMS_USER_ID, SMS_API_KEY and SMS_SENDER_ID to send real messages.";
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch(DEPLOYED_PROFILES::contains)) {
            log.error("{} This is a deployed profile ({}), where that is almost certainly wrong.",
                    banner, String.join(",", environment.getActiveProfiles()));
        } else {
            log.warn("{}", banner);
        }
    }

    @Override
    public void sendSms(String phoneNumber, String message) {
        // Do not log message bodies — they can contain OTPs/PII. Log length only.
        log.info("MOCK SMS to {} ({} chars) — not delivered", maskPhone(phoneNumber),
                message == null ? 0 : message.length());
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }
}
