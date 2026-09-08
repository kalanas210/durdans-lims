package com.uom.lims.validation;

import java.time.LocalDate;

/**
 * A request that declares an identity document together with the date of birth
 * it belongs to. Implemented by the patient payloads so
 * {@link com.uom.lims.validation.annotation.IdentityRequiredForAdults} can
 * decide whether the identity number is mandatory for that particular patient.
 */
public interface IdentityDeclaration {

    LocalDate getDob();

    String getIdentityNumber();
}
