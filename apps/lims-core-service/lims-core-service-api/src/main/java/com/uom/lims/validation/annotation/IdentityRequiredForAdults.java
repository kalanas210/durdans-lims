package com.uom.lims.validation.annotation;

import com.uom.lims.validation.validator.IdentityRequiredForAdultsValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint: the identity number is required only once the patient
 * is an adult. Children brought to the lab have no NIC of their own, so front
 * desk must be able to register them without inventing one.
 */
@Documented
@Constraint(validatedBy = IdentityRequiredForAdultsValidator.class)
@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface IdentityRequiredForAdults {
    String message() default "Identity number is required for patients aged 18 and over";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
