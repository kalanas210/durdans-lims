package com.uom.lims.validation.validator;

import com.uom.lims.validation.IdentityDeclaration;
import com.uom.lims.validation.annotation.IdentityRequiredForAdults;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDate;
import java.time.Period;

public class IdentityRequiredForAdultsValidator
        implements ConstraintValidator<IdentityRequiredForAdults, IdentityDeclaration> {

    /** Age at which a Sri Lankan is eligible for an NIC of their own. */
    public static final int ADULT_AGE = 18;

    @Override
    public boolean isValid(IdentityDeclaration request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }

        boolean hasIdentityNumber = request.getIdentityNumber() != null
                && !request.getIdentityNumber().isBlank();
        if (hasIdentityNumber) {
            return true;
        }

        // A missing DOB is @NotNull's problem, not ours. Reporting "identity
        // number required" as well would only bury the real error.
        LocalDate dob = request.getDob();
        if (dob == null) {
            return true;
        }

        if (isMinor(dob)) {
            return true;
        }

        // Report against the field the user has to fix, not the whole payload,
        // so the frontend can show the message next to the input.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("identityNumber")
                .addConstraintViolation();
        return false;
    }

    public static boolean isMinor(LocalDate dob) {
        return Period.between(dob, LocalDate.now()).getYears() < ADULT_AGE;
    }
}
