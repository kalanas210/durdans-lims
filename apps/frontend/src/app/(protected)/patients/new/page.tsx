"use client";

import { useRef, useState, type ChangeEvent, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { AlertCircle, FileCheck2, Loader2, UploadCloud, UserPlus, X } from "lucide-react";
import { isAxiosError } from "axios";
import { createPatient, uploadPatientDocument } from "@/lib/api";
import { cn } from "@/lib/utils";
import PageHeader from "@/components/ui/PageHeader";
import Button from "@/components/ui/Button";
import { FormSection, InputField, SelectField, TextareaField } from "@/components/ui/Field";

/**
 * Backend validation errors come back keyed by the PatientCreateRequest DTO
 * field names; map them onto the form's field names so they can be shown
 * inline next to the control that needs fixing.
 */
const SERVER_FIELD_TO_FORM_FIELD: Record<string, string> = {
    fullName: "fullName",
    phone: "phoneNumber",
    homeNumber: "alternatePhone",
};

const IDENTITY_PLACEHOLDER: Record<string, string> = {
    NIC: "e.g. 199012345678 or 921234567V",
    PASSPORT: "e.g. N1234567",
    DRIVING_LICENSE: "e.g. B1234567",
};

const IDENTITY_PATTERN: Record<string, string> = {
    NIC: "^([0-9]{9}[vVxX]|[0-9]{12})$",
    PASSPORT: "^[a-zA-Z]{1,2}[0-9]{7}$",
    DRIVING_LICENSE: "^[a-zA-Z0-9]{7,10}$",
};

const IDENTITY_HINT: Record<string, string> = {
    NIC: "Must be 9 digits followed by V/X or 12 digits",
    PASSPORT: "Must have 1 or 2 letters followed by 7 digits",
    DRIVING_LICENSE: "Must be 7-10 alphanumeric characters",
};

/** Age at which a patient is eligible for an NIC of their own. */
const ADULT_AGE = 18;

/**
 * Whole years between `dob` and today. Children are registered without an
 * identity document, so the form has to know the patient's age before it can
 * decide whether the identity number is required.
 */
function ageInYears(dob: string): number | null {
    if (!dob) return null;
    const born = new Date(`${dob}T00:00:00`);
    if (Number.isNaN(born.getTime())) return null;

    const today = new Date();
    let age = today.getFullYear() - born.getFullYear();
    const monthDiff = today.getMonth() - born.getMonth();
    if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < born.getDate())) {
        age -= 1;
    }
    return age;
}

export default function PatientRegistrationPage() {
    const router = useRouter();
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
    const [formData, setFormData] = useState({
        title: "",
        firstName: "",
        lastName: "",
        dob: "",
        gender: "",
        identityType: "NIC",
        identityNumber: "", // NIC/Passport/Driving License
        phoneNumber: "",
        alternatePhone: "",
        email: "",
        address: ""
    });

    // Document Upload State
    const [selectedFile, setSelectedFile] = useState<File | null>(null);
    const [documentType, setDocumentType] = useState("ID_VERIFICATION");
    const [docDescription, setDocDescription] = useState("");
    const [uploadProgress, setUploadProgress] = useState("");
    const fileInputRef = useRef<HTMLInputElement>(null);

    const clearFieldError = (field: string) => {
        setFieldErrors(prev => {
            if (!(field in prev)) return prev;
            const next = { ...prev };
            delete next[field];
            return next;
        });
    };

    const handleChange = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
        const { name, value } = e.target;
        setFormData(prev => ({ ...prev, [name]: value }));
        clearFieldError(name);
    };

    const handleFullNameChange = (e: ChangeEvent<HTMLInputElement>) => {
        const parts = e.target.value.trim().split(" ");
        const firstName = parts[0] || "";
        const lastName = parts.length > 1 ? parts.slice(1).join(" ") : "";
        setFormData(prev => ({ ...prev, firstName, lastName }));
        clearFieldError("fullName");
    };

    const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
        setSelectedFile(e.target.files ? e.target.files[0] : null);
    };

    const handleRemoveFile = () => {
        setSelectedFile(null);
        if (fileInputRef.current) fileInputRef.current.value = "";
    };

    const handleSubmit = async (e: FormEvent) => {
        e.preventDefault();
        setLoading(true);
        setError("");
        setFieldErrors({});
        setUploadProgress("Creating patient record...");

        try {
            // 1. Create Patient
            const patientResponse = await createPatient(formData);
            const patientCode = patientResponse.patientCode;

            // 2. Upload Document if selected
            if (selectedFile && patientCode) {
                setUploadProgress("Uploading document...");
                await uploadPatientDocument(patientCode, documentType, selectedFile, docDescription);
            }

            setUploadProgress("Success!");
            // On success, redirect to patients list
            router.push("/patients");
        } catch (err) {
            console.error("Registration failed", err);
            setUploadProgress("");
            if (isAxiosError(err)) {
                const data = err.response?.data;
                if (data?.details && typeof data.details === 'object') {
                    // Extract all field errors
                    const entries = Object.entries(data.details as Record<string, unknown>);
                    const fieldErrorText = entries
                        .map(([field, msg]) => `${field}: ${msg}`)
                        .join(", ");
                    setError(`Input validation failed: ${fieldErrorText}`);
                    // Also surface each message inline next to its field
                    const inline: Record<string, string> = {};
                    for (const [field, msg] of entries) {
                        inline[SERVER_FIELD_TO_FORM_FIELD[field] ?? field] = String(msg);
                    }
                    setFieldErrors(inline);
                } else {
                    setError(data?.message || "Failed to register patient. Please try again.");
                }
            } else {
                setError("Failed to register patient. Please try again.");
            }
        } finally {
            setLoading(false);
        }
    };

    const identityType = formData.identityType;
    const today = new Date().toISOString().split("T")[0];
    const age = ageInYears(formData.dob);
    // Under 18: no NIC of their own yet, so the identity number is optional.
    // Until a date of birth is entered we keep it required, so an adult can't
    // slip through by filling the form out of order.
    const isMinor = age !== null && age < ADULT_AGE;

    return (
        <div className="mx-auto max-w-5xl">
            <PageHeader
                crumbs={[{ label: "Patients", href: "/patients" }, { label: "Register patient" }]}
                title="Register patient"
                meta={<span>Create a new patient record. Fields marked * are required.</span>}
            />

            <div aria-live="assertive" role="alert">
                {error && (
                    <div className="mb-4 flex items-start gap-3 rounded-lg border border-status-danger-edge bg-status-danger-bg p-3 text-sm text-status-danger-fg">
                        <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                        <div className="min-w-0">
                            <p className="font-medium">Couldn&apos;t register patient</p>
                            <p className="mt-0.5 break-words">{error}</p>
                        </div>
                    </div>
                )}
            </div>

            <form onSubmit={handleSubmit}>
                <div className="space-y-4">
                    <FormSection title="Identity" description="Name, date of birth and identity document as they appear on the ID.">
                        <SelectField
                            id="title"
                            name="title"
                            label="Title"
                            required
                            value={formData.title}
                            onChange={handleChange}
                            error={fieldErrors.title}
                        >
                            <option disabled value="">Select title</option>
                            <option value="MR">Mr.</option>
                            <option value="MRS">Mrs.</option>
                            <option value="MS">Ms.</option>
                            <option value="MISS">Miss</option>
                            <option value="DR">Dr.</option>
                            <option value="PROF">Prof.</option>
                            <option value="REV">Rev.</option>
                        </SelectField>
                        <InputField
                            id="full_name"
                            name="fullName"
                            label="Full name"
                            required
                            type="text"
                            autoComplete="name"
                            placeholder="Patient's full legal name"
                            onChange={handleFullNameChange}
                            error={fieldErrors.fullName}
                        />
                        <InputField
                            id="dob"
                            name="dob"
                            label="Date of birth"
                            required
                            type="date"
                            max={today}
                            value={formData.dob}
                            onChange={handleChange}
                            error={fieldErrors.dob}
                        />
                        <SelectField
                            id="gender"
                            name="gender"
                            label="Gender"
                            required
                            value={formData.gender}
                            onChange={handleChange}
                            error={fieldErrors.gender}
                        >
                            <option disabled value="">Select gender</option>
                            <option value="male">Male</option>
                            <option value="female">Female</option>
                            <option value="other">Other</option>
                        </SelectField>
                        <SelectField
                            id="identityType"
                            name="identityType"
                            label="Identity type"
                            required
                            value={formData.identityType}
                            onChange={handleChange}
                            error={fieldErrors.identityType}
                        >
                            <option value="NIC">Sri Lankan NIC</option>
                            <option value="PASSPORT">Passport</option>
                            <option value="DRIVING_LICENSE">Driving license</option>
                        </SelectField>
                        <InputField
                            id="nic"
                            name="identityNumber"
                            label={isMinor ? "Identity number (optional)" : "Identity number"}
                            required={!isMinor}
                            type="text"
                            autoComplete="off"
                            style={{ textTransform: "uppercase" }}
                            placeholder={
                                isMinor
                                    ? "Leave blank if the child has no ID"
                                    : IDENTITY_PLACEHOLDER[identityType] ?? IDENTITY_PLACEHOLDER.DRIVING_LICENSE
                            }
                            pattern={IDENTITY_PATTERN[identityType] ?? IDENTITY_PATTERN.DRIVING_LICENSE}
                            title={IDENTITY_HINT[identityType] ?? IDENTITY_HINT.DRIVING_LICENSE}
                            hint={
                                isMinor
                                    ? `Patient is under ${ADULT_AGE} — an ID is not required. Enter one only if the child has a passport or NIC.`
                                    : IDENTITY_HINT[identityType] ?? IDENTITY_HINT.DRIVING_LICENSE
                            }
                            value={formData.identityNumber}
                            onChange={handleChange}
                            error={fieldErrors.identityNumber}
                        />
                    </FormSection>

                    <FormSection title="Contact" description="How the lab reaches the patient for results and verification.">
                        <InputField
                            id="phoneNumber"
                            name="phoneNumber"
                            label="Phone number"
                            required
                            type="tel"
                            autoComplete="tel"
                            placeholder="e.g. 0712345678"
                            pattern="^(?:0|\+94|94)7[01245678]\d{7}$"
                            title="Sri Lankan mobile number: e.g. 0712345678 or +94712345678"
                            hint="Sri Lankan mobile, e.g. 0712345678 or +94712345678"
                            value={formData.phoneNumber}
                            onChange={handleChange}
                            error={fieldErrors.phoneNumber}
                        />
                        <InputField
                            id="alternatePhone"
                            name="alternatePhone"
                            label="Alternative phone / home"
                            type="tel"
                            autoComplete="tel"
                            placeholder="e.g. 0112345678"
                            pattern="^(?:0|\+94|94)[1-9]\d{8}$"
                            title="Sri Lankan landline or mobile number: e.g. 0112345678 or +94112345678"
                            hint="Landline or mobile, e.g. 0112345678"
                            value={formData.alternatePhone}
                            onChange={handleChange}
                            error={fieldErrors.alternatePhone}
                        />
                        <InputField
                            id="email"
                            name="email"
                            label="Email address"
                            type="email"
                            autoComplete="email"
                            placeholder="patient@example.com"
                            value={formData.email}
                            onChange={handleChange}
                            error={fieldErrors.email}
                        />
                    </FormSection>

                    <FormSection title="Address">
                        <TextareaField
                            id="address"
                            name="address"
                            label="Residential address"
                            required
                            rows={3}
                            autoComplete="street-address"
                            placeholder="Complete residential address"
                            value={formData.address}
                            onChange={handleChange}
                            error={fieldErrors.address}
                            className="sm:col-span-2"
                        />
                    </FormSection>

                    <FormSection title="Document" description="Optional. Attach an ID copy or other supporting document to the new record.">
                        <SelectField
                            id="documentType"
                            name="documentType"
                            label="Document type"
                            value={documentType}
                            onChange={(e) => setDocumentType(e.target.value)}
                        >
                            <option value="LAB_REPORT_INTERNAL">Internal lab report</option>
                            <option value="LAB_REPORT_EXTERNAL">External lab report</option>
                            <option value="PRESCRIPTION">Prescription</option>
                            <option value="MEDICAL_HISTORY">Medical history</option>
                            <option value="ID_VERIFICATION">ID verification</option>
                            <option value="INSURANCE_DOCUMENT">Insurance document</option>
                            <option value="CONSENT_FORM">Consent form</option>
                            <option value="OTHER">Other</option>
                        </SelectField>
                        <InputField
                            id="docDescription"
                            name="docDescription"
                            label="Description"
                            type="text"
                            placeholder="e.g. NIC copy"
                            value={docDescription}
                            onChange={(e) => setDocDescription(e.target.value)}
                        />

                        <div className="sm:col-span-2">
                            <label
                                htmlFor="document-file"
                                className={cn(
                                    "relative flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed px-4 py-6 text-center transition-colors",
                                    "focus-within:ring-2 focus-within:ring-primary focus-within:ring-offset-1 focus-within:ring-offset-surface",
                                    selectedFile
                                        ? "border-primary bg-primary-soft"
                                        : "border-edge-strong hover:border-primary hover:bg-surface-muted"
                                )}
                            >
                                <input
                                    ref={fileInputRef}
                                    id="document-file"
                                    type="file"
                                    className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
                                    onChange={handleFileChange}
                                />
                                {selectedFile ? (
                                    <FileCheck2 className="mb-2 h-6 w-6 text-primary-strong" aria-hidden="true" />
                                ) : (
                                    <UploadCloud className="mb-2 h-6 w-6 text-fg-faint" aria-hidden="true" />
                                )}
                                <span className="block max-w-full truncate text-sm font-medium text-fg">
                                    {selectedFile ? selectedFile.name : "Click or drag a file to upload"}
                                </span>
                                <span className="mt-1 block text-xs text-fg-muted">PDF, JPG or PNG, up to 10 MB</span>
                            </label>
                            {selectedFile && (
                                <div className="mt-2 flex justify-end">
                                    <Button type="button" variant="ghost" size="sm" icon={X} onClick={handleRemoveFile}>
                                        Remove file
                                    </Button>
                                </div>
                            )}
                        </div>
                    </FormSection>
                </div>

                <div className="sticky bottom-0 z-10 mt-6 flex items-center justify-between gap-3 border-t border-edge bg-canvas py-3">
                    <div role="status" aria-live="polite" className="flex min-w-0 items-center gap-2 text-xs text-fg-muted">
                        {uploadProgress && (
                            <>
                                <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-primary-strong" aria-hidden="true" />
                                <span className="truncate font-medium text-fg-secondary">{uploadProgress}</span>
                            </>
                        )}
                    </div>
                    <div className="ml-auto flex shrink-0 items-center gap-2">
                        <Button href="/patients" variant="secondary">
                            Cancel
                        </Button>
                        <Button type="submit" variant="primary" icon={UserPlus} loading={loading}>
                            {loading ? "Registering…" : "Register patient"}
                        </Button>
                    </div>
                </div>
            </form>
        </div>
    );
}
