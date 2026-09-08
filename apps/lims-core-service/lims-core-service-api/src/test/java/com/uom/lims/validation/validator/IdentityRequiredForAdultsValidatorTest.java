package com.uom.lims.validation.validator;

import com.uom.lims.api.common.enums.Gender;
import com.uom.lims.api.common.enums.IdentityType;
import com.uom.lims.api.common.enums.Title;
import com.uom.lims.api.patient.dto.request.PatientCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule front desk actually works to: a child has no NIC of their own, so
 * registration must go through without one — but an adult still may not.
 */
class IdentityRequiredForAdultsValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static PatientCreateRequest.PatientCreateRequestBuilder patientBornYearsAgo(int years) {
        return PatientCreateRequest.builder()
                .title(Title.MR)
                .fullName("Nimal Perera")
                .dob(LocalDate.now().minusYears(years))
                .gender(Gender.MALE)
                .identityType(IdentityType.NIC)
                .phone("+94771234567");
    }

    private static Set<ConstraintViolation<PatientCreateRequest>> violationsOn(PatientCreateRequest request) {
        return validator.validate(request);
    }

    @Test
    void minorMayBeRegisteredWithoutAnIdentityNumber() {
        assertTrue(violationsOn(patientBornYearsAgo(7).build()).isEmpty());
        assertTrue(violationsOn(patientBornYearsAgo(7).identityNumber("").build()).isEmpty());
        assertTrue(violationsOn(patientBornYearsAgo(7).identityNumber("   ").build()).isEmpty());
    }

    @Test
    void patientTurning18TodayStillNeedsAnIdentityNumber() {
        Set<ConstraintViolation<PatientCreateRequest>> violations =
                violationsOn(patientBornYearsAgo(18).build());

        assertEquals(1, violations.size());
        ConstraintViolation<PatientCreateRequest> violation = violations.iterator().next();
        // Reported against the field so the form can show it inline.
        assertEquals("identityNumber", violation.getPropertyPath().toString());
    }

    @Test
    void adultWithAnIdentityNumberIsAccepted() {
        assertTrue(violationsOn(patientBornYearsAgo(34).identityNumber("199012345678").build()).isEmpty());
    }

    @Test
    void oneDayShortOf18IsStillAMinor() {
        PatientCreateRequest request = patientBornYearsAgo(18).build();
        request.setDob(LocalDate.now().minusYears(18).plusDays(1));

        assertTrue(violationsOn(request).isEmpty());
    }
}
