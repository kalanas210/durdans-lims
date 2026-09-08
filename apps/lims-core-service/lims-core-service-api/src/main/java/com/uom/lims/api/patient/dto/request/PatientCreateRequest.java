package com.uom.lims.api.patient.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uom.lims.api.common.enums.BloodGroup;
import com.uom.lims.api.common.enums.Gender;
import com.uom.lims.api.common.enums.IdentityType;
import com.uom.lims.api.common.enums.MaritalStatus;
import com.uom.lims.api.common.enums.Title;
import com.uom.lims.validation.IdentityDeclaration;
import com.uom.lims.validation.annotation.IdentityRequiredForAdults;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdentityRequiredForAdults
@Schema(name = "PatientCreateRequest", description = "Payload used to register a new patient in the LIMS system")
public class PatientCreateRequest implements IdentityDeclaration {

    @NotNull(message = "Title is required")
    @Schema(description = "Honorific title", example = "MR", requiredMode = Schema.RequiredMode.REQUIRED)
    private Title title;

    @NotBlank(message = "Full name is required")
    @Size(min = 1, max = 150, message = "Full name must be between 1 and 150 characters")
    @Schema(description = "Full name of the patient", example = "John Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fullName;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @JsonFormat(pattern = "yyyy-MM-dd")
    @Schema(description = "Date of birth of the patient", example = "1990-01-01", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate dob;

    @NotNull(message = "Gender is required")
    @Schema(description = "Gender of the patient", requiredMode = Schema.RequiredMode.REQUIRED)
    private Gender gender;

    @Schema(description = "Marital status of the patient")
    private MaritalStatus maritalStatus;

    @Schema(description = "Nationality of the patient", example = "Sri Lankan")
    private String nationality;

    @Schema(description = "Blood group of the patient", example = "O_POSITIVE")
    private BloodGroup bloodGroup;

    @NotNull(message = "Identity type is required")
    @Schema(description = "Type of identity document", example = "NIC", requiredMode = Schema.RequiredMode.REQUIRED)
    private IdentityType identityType;

    // Required for adults only: a patient under 18 has no NIC of their own, so
    // the class-level @IdentityRequiredForAdults decides instead of @NotBlank.
    // No minimum length: a blank value is how "this patient has no ID" arrives
    // from a form, and PatientService stores it as NULL.
    @Size(max = 50, message = "Identity number cannot exceed 50 characters")
    @Schema(description = "Identity number. Required for patients aged 18 and over; may be omitted for minors.",
            example = "123456789V")
    private String identityNumber;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Invalid phone number format")
    @Schema(description = "Contact phone number", example = "+94771234567", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @Email(message = "Invalid email format")
    @Schema(description = "Email address", example = "john.doe@example.com")
    private String email;

    @Size(min = 1, max = 20, message = "Home number must be between 1 and 20 characters")
    @Schema(description = "Landline number", example = "0112345678")
    private String homeNumber;

    @Size(max = 500, message = "Address cannot exceed 500 characters")
    @Schema(description = "Residential address", example = "123, Main Street, Colombo")
    private String address;

    @Schema(description = "Code of the branch where the patient is registered", example = "BR001")
    private String branchCode;

    @Schema(description = "Contact person name", example = "Jane Doe")
    private String contactPersonName;

    @Schema(description = "Contact person phone number", example = "+94771234568")
    private String contactPersonPhone;
}
