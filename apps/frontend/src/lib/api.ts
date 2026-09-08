import axiosInstance from './axios';
import {
    enrichLabTestWithWorkflowData,
    getOrderableLabTests,
    limsLabWorkflowData,
} from '@/data/lims-lab-workflow';

export interface Patient {
    id?: string;
    patientId?: string;
    patientCode?: string; // Backend identifier
    title?: string;
    firstName: string;
    lastName: string;
    fullName?: string; // Combined name
    dob?: string;
    gender?: string;
    identityType?: string;
    identityNumber?: string;
    phoneNumber?: string;
    phone?: string;
    alternatePhone?: string;
    email?: string;
    emailVerified?: boolean;
    phoneVerified?: boolean;
    profilePhotoUrl?: string;
    address?: string;
    bloodGroup?: string;
    maritalStatus?: string;
    nationality?: string;
    contactPersonName?: string;
    contactPersonPhone?: string;
    emergencyContactName?: string;
    emergencyContactRelation?: string;
    emergencyContactPhone?: string;
    createdAt?: string | number;
    updatedAt?: string | number;
    branchCode?: string;
    [key: string]: unknown;
}

export interface DashboardStatistics {
    patientsRegisteredToday: number;
    newPatientsThisWeek: number;
    pendingVerifications: number;
    todayTrend: string;
}

const blankToUndefined = (value?: string) => {
    return value && value.trim() ? value : undefined;
};

const normalizeLabTest = (test: any) => {
    const enriched = enrichLabTestWithWorkflowData(test);
    return {
        ...enriched,
        price: Number(enriched?.price ?? 0),
    };
};

// Inventory is counted per tube, so the offline catalog carries only the stock a tube maps to.
const DEFAULT_SUPPLY_TUBE_TYPES: Record<string, string> = {
    EDTA_PURPLE_TUBE: 'EDTA_PURPLE',
    SST_GOLD_TUBE: 'SST_GOLD',
    CITRATE_BLUE_TUBE: 'CITRATE_BLUE',
    HEPARIN_GREEN_TUBE: 'HEPARIN_GREEN',
    URINE_CONTAINER: 'URINE_YELLOW',
};

const getDefaultSupplies = () =>
    limsLabWorkflowData.supplies
        .filter((item: any) => Boolean(DEFAULT_SUPPLY_TUBE_TYPES[String(item.key)]))
        .map((item: any, index: number) => ({
            id: item.key,
            itemNo: item.itemNo ?? `SUP-${String(index + 1).padStart(4, '0')}`,
            itemNumber: item.itemNo ?? `SUP-${String(index + 1).padStart(4, '0')}`,
            name: item.label,
            category: item.category,
            tubeType: DEFAULT_SUPPLY_TUBE_TYPES[String(item.key)],
            tubeColor: item.tubeColor,
            currentStock: Number(item.currentStock ?? item.reorderStock ?? item.minStock ?? 0),
            minStock: Number(item.minStock ?? 0),
            maxStock: Number(item.maxStock ?? (Number(item.reorderStock ?? item.minStock ?? 0) * 2)),
            unit: item.unit ?? 'units',
            lastRestocked: item.lastRestocked ?? new Date().toISOString().slice(0, 10),
            expiryDate: '-',
        }));

export const getPatients = async (params?: Record<string, unknown>) => {
    // Backend search expects keyword, fullName, phone, identityNumber, email, etc.
    const response = await axiosInstance.get('/api/v1/patients', { params });
    // Spring Boot PageResponse structure contains 'content' array
    return response.data;
};

export const createPatient = async (data: Partial<Patient>) => {
    // Map frontend specific fields to backend PatientCreateRequest DTO
    const payload = {
        title: data.title || 'MR',
        fullName: `${data.firstName} ${data.lastName}`.trim(),
        dob: data.dob,
        gender: (data.gender || 'MALE').toUpperCase(),
        maritalStatus: blankToUndefined(data.maritalStatus),
        nationality: blankToUndefined(data.nationality),
        bloodGroup: data.bloodGroup ? data.bloodGroup.toUpperCase() : undefined,
        identityType: (data.identityType || 'NIC').toUpperCase(),
        // Left blank for patients under 18, who have no NIC of their own; the
        // backend accepts the omission for minors and rejects it for adults.
        identityNumber: blankToUndefined(data.identityNumber),
        phone: (data.phoneNumber || data.phone || '').replace(/\s+/g, ''),
        email: data.email || undefined,
        homeNumber: data.alternatePhone ? data.alternatePhone.replace(/\s+/g, '') : undefined,
        address: data.address || 'N/A',
        branchCode: data.branchCode,
        contactPersonName: data.contactPersonName,
        contactPersonPhone: data.contactPersonPhone ? data.contactPersonPhone.replace(/\s+/g, '') : undefined
    };

    const response = await axiosInstance.post('/api/v1/patients', payload);
    return response.data;
};

export const getPatientById = async (id: string) => {
    // The backend uses patientCode (our 'id')
    const response = await axiosInstance.get(`/api/v1/patients/${id}`);

    // Reverse map the backend PatientResponse to our frontend Patient interface
    const p = response.data;
    const nameParts = p.fullName ? p.fullName.split(' ') : [];
    const firstName = nameParts[0] || '';
    const lastName = nameParts.length > 1 ? nameParts.slice(1).join(' ') : '';

    return {
        ...p,
        id: p.patientCode, // Make sure frontend 'id' matches backend 'patientCode'
        firstName,
        lastName,
        phoneNumber: p.phone,
        alternatePhone: p.homeNumber
    } as Patient;
};

export const updatePatient = async (id: string, data: Partial<Patient>) => {
    // Map frontend specific fields to backend PatientUpdateRequest DTO
    const payload = {
        title: data.title,
        fullName: `${data.firstName} ${data.lastName}`.trim(),
        dob: data.dob,
        gender: data.gender ? data.gender.toUpperCase() : undefined,
        maritalStatus: blankToUndefined(data.maritalStatus),
        nationality: blankToUndefined(data.nationality),
        bloodGroup: data.bloodGroup ? data.bloodGroup.toUpperCase() : undefined,
        identityType: data.identityType ? data.identityType.toUpperCase() : undefined,
        identityNumber: data.identityNumber,
        phone: (data.phoneNumber || data.phone || '').replace(/\s+/g, ''),
        email: data.email || undefined,
        homeNumber: data.alternatePhone ? data.alternatePhone.replace(/\s+/g, '') : undefined,
        address: data.address,
        branchCode: data.branchCode,
        contactPersonName: data.contactPersonName,
        contactPersonPhone: data.contactPersonPhone ? data.contactPersonPhone.replace(/\s+/g, '') : undefined
    };

    const response = await axiosInstance.put(`/api/v1/patients/${id}`, payload);

    // Reverse map just like getPatientById
    const p = response.data;
    const nameParts = p.fullName ? p.fullName.split(' ') : [];

    return {
        ...p,
        id: p.patientCode,
        firstName: nameParts[0] || '',
        lastName: nameParts.length > 1 ? nameParts.slice(1).join(' ') : '',
        phoneNumber: p.phone,
        alternatePhone: p.homeNumber
    } as Patient;
};

// Document API Endpoints
export interface PatientDocument {
    documentId: string;
    documentType: string;
    originalFileName: string;
    description?: string | null;
    contentType: string;
    fileSize: number;
    uploadedAt: string;
    uploadedBy?: string | null;
    uploadedBranch?: string | null;
}

export interface PatientDocumentPage {
    content: PatientDocument[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
}

export const uploadPatientDocument = async (patientCode: string, documentType: string, file: File, description?: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('documentType', documentType);
    if (description) {
        formData.append('description', description);
    }

    const response = await axiosInstance.post(`/api/v1/patients/${patientCode}/documents`, formData, {
        headers: {
            'Content-Type': 'multipart/form-data'
        }
    });
    return response.data;
};

export const getPatientDocuments = async (patientCode: string, params?: Record<string, unknown>) => {
    const response = await axiosInstance.get(`/api/v1/patients/${patientCode}/documents`, { params });
    return response.data as PatientDocumentPage;
};

export const downloadPatientDocument = async (patientCode: string, documentId: string) => {
    const response = await axiosInstance.get(`/api/v1/patients/${patientCode}/documents/${documentId}/download`);
    return response.data; // This returns the presigned URL string
};

export const deletePatientDocument = async (patientCode: string, documentId: string) => {
    await axiosInstance.delete(`/api/v1/patients/${patientCode}/documents/${documentId}`);
};

export const getDashboardStatistics = async (branchCode?: string) => {
    const params = branchCode ? { branchCode } : {};
    const response = await axiosInstance.get('/api/v1/patients/statistics', { params });
    return response.data as DashboardStatistics;
};

export const resendEmailVerification = async (patientCode: string) => {
    const response = await axiosInstance.post(`/api/v1/patients/${patientCode}/resend-verification`);
    return response.data;
};

export const sendPhoneOtp = async (patientCode: string) => {
    const response = await axiosInstance.post(`/api/v1/patients/${patientCode}/send-phone-otp`);
    return response.data;
};

export const verifyPhoneOtp = async (patientCode: string, otp: string) => {
    const response = await axiosInstance.post(`/api/v1/patients/${patientCode}/verify-phone-otp`, null, {
        params: { otp }
    });
    return response.data;
};

export const uploadProfilePhoto = async (patientCode: string, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    const response = await axiosInstance.post(`/api/v1/patients/${patientCode}/profile-photo`, formData, {
        headers: {
            'Content-Type': 'multipart/form-data'
        }
    });
    return response.data as string;
};

export const getProfilePhoto = async (patientCode: string) => {
    const response = await axiosInstance.get(`/api/v1/patients/${patientCode}/profile-photo`);
    return response.data as { url: string };
};

export interface NavItem {
    displayText: string;
    linkUrl: string;
}

export interface AppMetadata {
    currentBranchName: string;
    currentBranchCode: string;
    navItems: NavItem[];
}

export const getMetadata = async () => {
    const response = await axiosInstance.get('/api/v1/metadata');
    return response.data as AppMetadata;
};

// --- Audit Logs ---

export interface AuditLog {
    id: string;
    action: string;
    entityType: string;
    entityId?: string;
    patientCode?: string;
    performedBy: string;
    branchCode: string;
    ipAddress?: string;
    timestamp: string;
    details?: string;
}

export interface AuditLogPage {
    content: AuditLog[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
}

export const getAuditLogs = async (params?: Record<string, unknown>) => {
    // If startDate/endDate exist, ensure they're valid ISO strings before passing
    const finalParams = { ...params };
    if (finalParams.startDate instanceof Date) {
        finalParams.startDate = finalParams.startDate.toISOString();
    }
    if (finalParams.endDate instanceof Date) {
        finalParams.endDate = finalParams.endDate.toISOString();
    }
    const response = await axiosInstance.get('/api/v1/audit-logs', { params: finalParams });
    return response.data as AuditLogPage;
};

// --- Report dispatch ---

export type ApiDeliveryMethod = 'EMAIL' | 'SMS' | 'WHATSAPP' | 'POST' | 'PRINT' | 'PORTAL';
export type ApiDispatchItemStatus = 'PENDING' | 'PARTIAL' | 'DELIVERED' | 'FAILED';
export type ApiDeliveryAttemptStatus = 'PENDING' | 'SENT' | 'DELIVERED' | 'FAILED';

export interface DispatchDashboardItem {
    id: string;
    reportId: string;
    patientName: string;
    patientId: string;
    testName: string;
    authorizedDate: string;
    authorizedTime: string;
    deliveryMethods: ApiDeliveryMethod[];
    status: ApiDispatchItemStatus;
    authorizedBy?: string | null;
    priorityLevel?: string | null;
}

export interface DeliveryAttempt {
    id: string;
    method: ApiDeliveryMethod;
    status: ApiDeliveryAttemptStatus;
    failureReason?: string | null;
    retryCount: number;
    dispatchedAt?: string | null;
    deliveredAt?: string | null;
    recipientContact?: string | null;
    trackingNumber?: string | null;
    trackingUrl?: string | null;
}

export interface DispatchReportResult {
    parameter?: string | null;
    result?: string | null;
    unit?: string | null;
    flag?: string | null;
    referenceRange?: string | null;
    abnormal?: boolean | null;
}

export interface DispatchItemDetail {
    id: string;
    reportReference: string;
    branchCode: string;
    patientCode?: string | null;
    patientDisplayName: string;
    patientAge?: number | null;
    patientGender?: string | null;
    patientDob?: string | null;
    referringDoctor?: string | null;
    ward?: string | null;
    testPanelLabel: string;
    sampleId?: string | null;
    sampleCollectedAt?: string | null;
    reportGeneratedAt?: string | null;
    authorizedBy?: string | null;
    clinicalNote?: string | null;
    results?: DispatchReportResult[] | null;
    artifactUri?: string | null;
    authorizedAt: string;
    overallStatus: ApiDispatchItemStatus;
    preferredDeliveryMethods: ApiDeliveryMethod[];
    attempts: DeliveryAttempt[];
}

export interface DeliveryRecordRow {
    reportId: string;
    patientName: string;
    patientCode?: string | null;
    testName: string;
    authorizedBy?: string | null;
    methods: ApiDeliveryMethod[];
    status: ApiDispatchItemStatus;
    dispatchedTime: string;
    deliveredTime: string | null;
    trackingNumber?: string | null;
    trackingUrl?: string | null;
    updatedAt?: string | null;
    dispatchedBy?: string | null;
}

export interface FailedDeliveryRow {
    attemptId: string;
    reportId: string;
    patientName: string;
    patientCode?: string | null;
    testName: string;
    method: ApiDeliveryMethod;
    failureReason: string;
    failedDateTime: string;
    retryCount: number;
    dispatchedBy?: string | null;
    recipientContact?: string | null;
}

export interface PageResponseDispatch<T> {
    content: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
}

export const registerAuthorizedReport = async (body: {
    reportReference: string;
    branchCode: string;
    patientCode?: string;
    patientDisplayName: string;
    testPanelLabel: string;
    artifactUri?: string;
    authorizedAt?: string;
    preferredDeliveryMethods?: ApiDeliveryMethod[];
}) => {
    const response = await axiosInstance.post('/api/v1/dispatch/reports/register', body);
    return response.data as DispatchItemDetail;
};

export const listDispatchReports = async (params?: Record<string, unknown>) => {
    const response = await axiosInstance.get('/api/v1/dispatch/reports', { params });
    return response.data as PageResponseDispatch<DispatchDashboardItem>;
};

export const getDispatchReport = async (reportReference: string, branchCode?: string) => {
    const enc = encodeURIComponent(reportReference);
    const response = await axiosInstance.get(`/api/v1/dispatch/reports/${enc}`, {
        params: branchCode ? { branchCode } : undefined
    });
    return response.data as DispatchItemDetail;
};

export const listDeliveryRecords = async (params?: Record<string, unknown>) => {
    const response = await axiosInstance.get('/api/v1/dispatch/delivery-records', { params });
    return response.data as PageResponseDispatch<DeliveryRecordRow>;
};

export const listFailedDeliveries = async (params?: { branchCode?: string; limit?: number }) => {
    const response = await axiosInstance.get('/api/v1/dispatch/failed-deliveries', { params });
    return response.data as FailedDeliveryRow[];
};

export const dispatchReport = async (
    reportReference: string,
    body: {
        methods: ApiDeliveryMethod[];
        overrideEmail?: string;
        overridePhone?: string;
        overrideWhatsappPhone?: string;
        postalAddress?: string;
        postalService?: string;
        trackingNumber?: string;
    },
    branchCode?: string
) => {
    const enc = encodeURIComponent(reportReference);
    const response = await axiosInstance.post(`/api/v1/dispatch/reports/${enc}/dispatch`, body, {
        params: branchCode ? { branchCode } : undefined
    });
    return response.data as DispatchItemDetail;
};

export const retryDispatchAttempt = async (attemptId: string) => {
    const response = await axiosInstance.post(`/api/v1/dispatch/attempts/${attemptId}/retry`);
    return response.data as DispatchItemDetail;
};

export const markDispatchAttemptDelivered = async (attemptId: string) => {
    const response = await axiosInstance.post(`/api/v1/dispatch/attempts/${attemptId}/mark-delivered`);
    return response.data as DispatchItemDetail;
};

/** Records revenue report page view or export; performedBy is set server-side from the auth context. */
export const logRevenueReportAccess = async (payload: { event: 'VIEW' | 'EXPORT'; detail?: string }) => {
    await axiosInstance.post('/api/v1/audit-logs/revenue-report-access', payload);
};

// --- Verification & Clinical Authorization ---

export interface TestResultParameter {
    parameterCode: string;
    parameterName: string;
    resultValue?: number | null;
    resultText?: string | null;
    unit?: string | null;
    referenceRangeLow?: number | null;
    referenceRangeHigh?: number | null;
    flag?: string | null;
    /** Delta check: the patient's most recent released value for this parameter (previous visit). */
    previousValue?: string | null;
    previousFlag?: string | null;
    previousVisitedAt?: string | null;
    previousSampleBarcode?: string | null;
    /** current - previous, when both are numeric */
    deltaAbsolute?: number | null;
    /** Signed percent change vs the previous value */
    deltaPercent?: number | null;
    /** |deltaPercent| exceeds the lab's delta-check threshold */
    deltaSignificant?: boolean | null;
}

export interface PreviousVisitSummary {
    resultId: string;
    /** Human-readable case number of that visit (RES2026-00042) */
    resultNo?: string | null;
    sampleId: string;
    status?: string | null;
    priorityLevel?: string | null;
    visitedAt?: string | null;
    parameterCount?: number | null;
    abnormalCount?: number | null;
    criticalCount?: number | null;
}

export interface TestResultSummary {
    resultId: string;
    /** Human-readable case number (RES2026-00042); the UUID stays the routing key */
    resultNo?: string | null;
    status?: string | null;
    /** Patient code, so the queue can be searched by ID as well as by name */
    patientCode?: string | null;
    patientName?: string | null;
    testType?: string | null;
    mltName?: string | null;
    qcStatus?: string | null;
    flag?: string | null;
    /** Specimen urgency from accessioning: STAT, URGENT, NORMAL */
    priorityLevel?: string | null;
    /** Any parameter on this specimen has a critical panic flag */
    hasCriticalFinding?: boolean | null;
    createdAt?: string | null;
    updatedAt?: string | null;
    technicianName?: string | null;
    pathologistName?: string | null;
    returnReason?: string | null;
}

export interface TestResultDetail {
    resultId: string;
    /** Human-readable case number (RES2026-00042) */
    resultNo?: string | null;
    status?: string | null;
    patientCode?: string | null;
    patientName?: string | null;
    patientAge?: number | null;
    patientGender?: string | null;
    testType?: string | null;
    priority?: string | null;
    createdAt?: string | null;
    updatedAt?: string | null;
    mltName?: string | null;
    supervisorName?: string | null;
    technicianName?: string | null;
    pathologistName?: string | null;
    authorizedAt?: string | null;
    parameters: TestResultParameter[];
    previousVisits?: PreviousVisitSummary[] | null;
    clinicalNote?: string | null;
    mltNotes?: string | null;
    supervisorNote?: string | null;
    /** Specimen / encounter context for the review header */
    sampleBarcode?: string | null;
    tubeType?: string | null;
    collectedAt?: string | null;
    collectedBy?: string | null;
    /** When accessioning accepted the specimen into the lab */
    receivedAt?: string | null;
    /** When the analyser / MLT recorded the latest value */
    measuredAt?: string | null;
    referringDoctor?: string | null;
    referringDepartment?: string | null;
    /** Last return on the case, in either direction */
    returnReason?: string | null;
    returnedBy?: string | null;
    returnedAt?: string | null;
}

export interface TestResultPage {
    content: TestResultSummary[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
}

export interface VerificationPayload {
    resultId?: string;
    status?: string;
    mltNotes?: string;
    supervisorNote?: string;
    qcOverrideReason?: string;
}

export interface BulkVerificationPayload {
    resultIds: string[];
    status?: string;
    mltNotes?: string;
    /** Supervisor's remark for the whole batch, from the confirmation modal */
    supervisorNote?: string;
}

export interface BulkVerificationParameterPreview {
    parameterName?: string | null;
    resultValue?: string | null;
    unit?: string | null;
    flag?: string | null;
}

/** One case (specimen) on the bulk approval worklist — enough for a card. */
export interface BulkVerificationCase {
    /** Anchor result id: the id the case is approved or reviewed through */
    resultId: string;
    resultNo?: string | null;
    sampleId?: string | null;
    sampleBarcode?: string | null;
    patientCode?: string | null;
    patientName?: string | null;
    priorityLevel?: string | null;
    status?: string | null;
    flag?: string | null;
    hasCriticalFinding?: boolean | null;
    safeForApproval: boolean;
    updatedAt?: string | null;
    parameterCount: number;
    parameters: BulkVerificationParameterPreview[];
}

export interface BulkVerificationBatch {
    batchId: string;
    batchName: string;
    batchCode: string;
    department: string;
    totalResults: number;
    safeForApproval: number;
    exceptions: number;
    updatedAt?: string | null;
    resultIds: string[];
    reviewResultIds: string[];
    /** Every case in this test group, safe and held alike */
    cases?: BulkVerificationCase[] | null;
}

export interface VerificationHistoryItem {
    resultId: string;
    /** Human-readable case number (RES2026-00042) of the result the action was taken on */
    resultNo?: string | null;
    actionType?: string | null;
    patientCode?: string | null;
    patientName?: string | null;
    testName?: string | null;
    specimenPriority?: string | null;
    actionSummary?: string | null;
    performedBy?: string | null;
    actionAt?: string | null;
    notes?: string | null;
    updatedAt?: string | null;
}

export interface VerificationHistoryPage {
    content: VerificationHistoryItem[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
}

export interface HistoryQueryParams {
    actionType?: string;
    search?: string;
    /** ISO date-time lower bound; omit for all time. Drives the Today / 7d / 30d filters. */
    fromTimestamp?: string;
}

export interface ClinicalAuthorizationPayload {
    resultId?: string;
    status?: string;
    clinicalNote?: string;
    signatureConfirmed?: boolean;
}

export interface ReturnToMltPayload {
    resultId?: string;
    status?: string;
    returnReason: string;
}

export const getPendingVerificationResults = async (page = 0, size = 10) => {
    const response = await axiosInstance.get('/api/v1/verification/pending-results', {
        params: { page, size }
    });
    return response.data as TestResultPage;
};

export const getVerificationResultDetails = async (resultId: string) => {
    const response = await axiosInstance.get(`/api/v1/verification/${resultId}`);
    return response.data as TestResultDetail;
};

export const approveTechnically = async (resultId: string, payload: VerificationPayload = {}) => {
    const response = await axiosInstance.post(`/api/v1/verification/${resultId}/verify`, payload);
    return response.data as TestResultDetail;
};

export const rejectTechnically = async (resultId: string, payload: VerificationPayload = {}) => {
    const response = await axiosInstance.post(`/api/v1/verification/${resultId}/reject`, payload);
    return response.data as TestResultDetail;
};

export const bulkApproveTechnically = async (payload: BulkVerificationPayload) => {
    const response = await axiosInstance.post('/api/v1/verification/bulk-verify', payload);
    return response.data as Record<string, string>;
};

export const getBulkVerificationWorklist = async () => {
    const response = await axiosInstance.get('/api/v1/verification/bulk/worklist');
    return response.data as BulkVerificationBatch[];
};

export const getVerificationHistory = async (
    page = 0,
    size = 10,
    filters: HistoryQueryParams = {}
) => {
    const response = await axiosInstance.get('/api/v1/verification/history', {
        params: {
            page,
            size,
            actionType: filters.actionType,
            search: filters.search,
            fromTimestamp: filters.fromTimestamp
        }
    });
    return response.data as VerificationHistoryPage;
};

export const getPendingClinicalResults = async (page = 0, size = 10) => {
    const response = await axiosInstance.get('/api/v1/clinical/pending', {
        params: { page, size }
    });
    return response.data as TestResultPage;
};

export const getClinicalResultDetails = async (resultId: string) => {
    const response = await axiosInstance.get(`/api/v1/clinical/${resultId}`);
    return response.data as TestResultDetail;
};

export const getClinicalHistory = async (
    page = 0,
    size = 10,
    filters: HistoryQueryParams = {}
) => {
    const response = await axiosInstance.get('/api/v1/clinical/history', {
        params: {
            page,
            size,
            actionType: filters.actionType,
            search: filters.search,
            fromTimestamp: filters.fromTimestamp
        }
    });
    return response.data as VerificationHistoryPage;
};

export const authorizeClinical = async (
    resultId: string,
    payload: ClinicalAuthorizationPayload = {}
) => {
    const response = await axiosInstance.post(`/api/v1/clinical/${resultId}/authorize`, payload);
    return response.data as TestResultDetail;
};

export const returnForRecheck = async (resultId: string, payload: ReturnToMltPayload) => {
    const response = await axiosInstance.post(`/api/v1/clinical/${resultId}/return`, payload);
    return response.data as TestResultDetail;
};

// --- MLT & Reception ---

export type RejectionReason =
    | 'HEMOLYZED'
    | 'INSUFFICIENT_VOLUME'
    | 'CLOTTED'
    | 'CONTAMINATED'
    | 'OTHER';

export interface MltWorklistItem {
    sampleId: string;
    barcode: string;
    orderId: string;
    patientId: string;
    testName: string;
    priority: 'STAT' | 'URGENT' | 'NORMAL';
    status: string;
    collectedAt?: string | null;
    /** The supervisor returned this case to the MLT; it awaits re-entry */
    returnedToMlt?: boolean | null;
    returnReason?: string | null;
}

export interface SampleRejectRequest {
    rejectionReason: RejectionReason;
    rejectionNotes?: string;
}

export interface PreviousLabValue {
    result: string;
    flag: string | null;
    collectedAt: string | null;
    sampleBarcode: string;
}

export interface ResultParameter {
    parameterId: string;
    parameterName: string;
    result: string | null;
    unit: string | null;
    refLow: number | null;
    refHigh: number | null;
    flag: string | null;
    previousValue?: PreviousLabValue | null;
}

export interface SampleResults {
    sampleId: string;
    barcode: string;
    orderId: string;
    orderNo?: string | null;
    orderItemId?: string | null;
    patientId: string;
    patientName: string;
    testName: string;
    status: string;
    tubeType?: string | null;
    priority?: string | null;
    collectedAt?: string | null;
    collectedBy?: string | null;
    mltNotes: string | null;
    results: ResultParameter[];
    /** The supervisor returned this case to the MLT; it awaits re-entry */
    returnedToMlt?: boolean | null;
    returnReason?: string | null;
    returnedBy?: string | null;
    returnedAt?: string | null;
}

export interface MltResultActivityItem {
    id: string;
    action: string;
    performedBy: string;
    timestamp: string;
    details: string | null;
}

export interface SubmitResultsRequest {
    sampleId: string;
    results: Array<{
        parameterId: string;
        result: string;
        flag?: string;
    }>;
    mltNotes?: string;
    /**
     * Which analyser produced these values, as a registry code (or BENCH-MANUAL).
     * The QC release gate uses it to find the control governing the result; without
     * it the result is neither held nor vouched for.
     */
    instrumentCode?: string;
}

export interface MltAllWorklistItem {
    sampleId: string;
    barcode: string;
    orderId: string;
    patientId: string;
    patientName: string;
    testName: string;
    department: string;
    priority: 'STAT' | 'URGENT' | 'NORMAL';
    status: string;
    collectedAt: string | null;
}

/** Search/print row: `id` is the specimen UUID; `sampleId` is the human-readable barcode. */
export interface SamplePrintItem {
    id: string;
    sampleId: string;
    orderId: string | null;
    priority: 'STAT' | 'URGENT' | 'NORMAL';
    testType: string | null;
    testCodes: string[] | null;
    tubeTypes: string[] | null;
    /** Swatch recorded on the tube's supply row; null when no supply row stocks that tube yet. */
    tubeColor: string | null;
    waitTimeMinutes: number;
    status: string;
    patient: {
        pid: string | null;
    } | null;
    collectedAt: string | null;
    collectedBy: string | null;
    rejectionReason?: string | null;
}

export interface QcRunItem {
    id: string;
    instrument: string;
    testGroup: string;
    level: string;
    result: string;
    expected: string;
    sd: string;
    status: 'PASS' | 'WARN' | 'FAIL';
    performedBy: string;
    timestamp: string;
}

export interface QcDashboardData {
    totalRuns: number;
    passed: number;
    warnings: number;
    failures: number;
    runs: QcRunItem[];
}

export interface InstrumentStatusItem {
    id: string;
    name: string;
    type: string;
    model: string;
    serial: string;
    status: 'online' | 'offline' | 'busy';
    lastSync: string;
    testsToday: number;
    location: string;
    qcStatus: 'PASS' | 'WARN' | 'FAIL';
}

export const getReceptionSamples = async () => {
    const response = await axiosInstance.get('/api/v1/reception/samples');
    return response.data as MltWorklistItem[];
};

export const searchSamplesForPrint = async (query: string) => {
    const response = await axiosInstance.get('/api/v1/reception/samples/search', {
        params: { query },
    });
    return response.data as SamplePrintItem[];
};

export const acceptSample = async (id: string) => {
    await axiosInstance.post(`/api/v1/reception/samples/${id}/accept`);
};

export const rejectSample = async (id: string, payload: SampleRejectRequest) => {
    await axiosInstance.post(`/api/v1/reception/samples/${id}/reject`, payload);
};

export const getMltWorklist = async () => {
    const response = await axiosInstance.get('/api/v1/mlt/worklist');
    return response.data as MltWorklistItem[];
};

export const getMltAllWorklist = async () => {
    const response = await axiosInstance.get('/api/v1/mlt/all-worklist');
    return response.data as MltAllWorklistItem[];
};

export const getQcDashboard = async () => {
    const response = await axiosInstance.get('/api/v1/mlt/qc-dashboard');
    return response.data as QcDashboardData;
};

export const getInstruments = async () => {
    const response = await axiosInstance.get('/api/v1/mlt/instruments');
    return response.data as InstrumentStatusItem[];
};

export const syncInstrument = async (id: string) => {
    const response = await axiosInstance.post(`/api/v1/mlt/instruments/${id}/sync`);
    return response.data as InstrumentStatusItem;
};

export const getSampleResults = async (id: string) => {
    const response = await axiosInstance.get(`/api/v1/mlt/samples/${id}/results`);
    return response.data as SampleResults;
};

export const getMltSampleResultActivity = async (id: string) => {
    const response = await axiosInstance.get(`/api/v1/mlt/samples/${id}/result-activity`);
    return response.data as MltResultActivityItem[];
};

export const saveDraftResults = async (id: string, payload: SubmitResultsRequest) => {
    await axiosInstance.post(`/api/v1/mlt/samples/${id}/results/draft`, payload);
};

export const submitResults = async (id: string, payload: SubmitResultsRequest) => {
    await axiosInstance.post(`/api/v1/mlt/samples/${id}/results`, payload);
};

// ============ LAB TESTS ============
export const getLabTests = async () => {
    try {
        const response = await axiosInstance.get('/api/v1/tests');
        const tests = response.data.data;
        if (Array.isArray(tests) && tests.length > 0) {
            return tests.map(normalizeLabTest);
        }
    } catch (error) {
        console.warn('Falling back to local lab catalog because /api/v1/tests failed.', error);
    }

    return getOrderableLabTests().map(normalizeLabTest);
};

// ============ ORDERS ============
export const createOrder = async (data: {
    patientId: string;
    testIds: string[];
    priority: 'STAT' | 'URGENT' | 'NORMAL';
    testPriorities?: Record<string, 'STAT' | 'URGENT' | 'NORMAL'>;
    referringDoctor?: string;
    referringDepartment?: string;
    remarks?: string;
}) => {
    const response = await axiosInstance.post('/api/v1/orders', data);
    return response.data.data;
};

export const getOrders = async (page = 0, size = 10, params: Record<string, unknown> = {}) => {
    const response = await axiosInstance.get('/api/v1/orders', {
        params: { page, size, ...params },
    });
    return response.data.data;
};

export const getPatientOrders = async (patientCode: string, page = 0, size = 20) => {
    return getOrders(page, size, {
        patientId: patientCode,
        sort: 'createdAt,desc',
    });
};

export const getPatientReports = async (patientCode: string, page = 0, size = 20) => {
    const response = await listDispatchReports({
        keyword: patientCode,
        page,
        size,
        sort: 'authorizedAt,desc',
    });
    const content = response.content.filter((report) => report.patientId === patientCode);

    return {
        ...response,
        content,
        totalElements: content.length,
        totalPages: content.length > 0 ? 1 : 0,
        last: true,
    };
};

export const getOrderById = async (id: string) => {
    const response = await axiosInstance.get(`/api/v1/orders/${id}`);
    return response.data.data;
};

export const getOrderTracking = async (id: string) => {
    const response = await axiosInstance.get(`/api/v1/orders/${id}/tracking`);
    return response.data.data;
};

export const cancelOrder = async (id: string) => {
    const response = await axiosInstance.patch(`/api/v1/orders/${id}/cancel`);
    return response.data.data;
};

// ============ BILLING ============
export const getBillByOrderId = async (orderId: string) => {
    const response = await axiosInstance.get(`/api/v1/billing/orders/${orderId}/bill`);
    return response.data.data;
};

export const getBillById = async (billId: string) => {
    const response = await axiosInstance.get(`/api/v1/billing/bills/${billId}`);
    return response.data.data;
};

export const applyDiscount = async (billId: string, data: {
    discountAmount: number;
    reason: string;
}) => {
    const response = await axiosInstance.patch(`/api/v1/billing/bills/${billId}/discount`, data);
    return response.data.data;
};

export const processPayment = async (billId: string, data: {
    billId: string;
    amount: number;
    paymentMethod: 'CASH' | 'CREDIT_CARD' | 'DEBIT_CARD' | 'BANK_TRANSFER' | 'INSURANCE';
    bankReferenceNo?: string;
    bankName?: string;
    insuranceClaimNo?: string;
    notes?: string;
}) => {
    const response = await axiosInstance.post(`/api/v1/billing/bills/${billId}/payments`, data);
    return response.data.data;
};

export const printBill = async (billId: string) => {
    const response = await axiosInstance.post(`/api/v1/billing/bills/${billId}/print`);
    return response.data.data;
};

export const getOrdersBillingStats = async (period?: string) => {
    const response = await axiosInstance.get('/api/v1/orders-billing/statistics');
    return response.data.data;
};

// ============ PHLEBOTOMY ============
export const getPhlebotomyStats = async () => {
    const response = await axiosInstance.get('/api/v1/phlebotomy/statistics');
    return response.data.data;
};

export const getPhlebotomyWorklist = async (page = 0, size = 10) => {
    const response = await axiosInstance.get(`/api/v1/phlebotomy/worklist?page=${page}&size=${size}`);
    return response.data.data;
};

export const collectSample = async (sampleId: string, data: { notes?: string }) => {
    const response = await axiosInstance.post(`/api/v1/phlebotomy/samples/${sampleId}/collect`, data);
    return response.data.data;
};

export const rejectPhlebotomySample = async (sampleId: string, data: {
    rejectionReason: string;
    rejectionNotes?: string;
}) => {
    const response = await axiosInstance.post(`/api/v1/phlebotomy/samples/${sampleId}/reject`, data);
    return response.data.data;
};

export const getCollectionHistory = async (page = 0, size = 10) => {
    const response = await axiosInstance.get(`/api/v1/phlebotomy/collection-history?page=${page}&size=${size}`);
    return response.data.data;
};

export interface SpecimenSampleDetail {
    id: string;
    sampleId: string;
    orderId: string | null;
    priority: string;
    testType: string | null;
    testCodes: string[] | null;
    tubeTypes: string[] | null;
    waitTimeMinutes: number;
    status: string;
    patient: {
        name?: string | null;
        pid?: string | null;
        age?: number | null;
        gender?: string | null;
        wardRoom?: string | null;
    } | null;
    collectedAt?: string | null;
    collectedBy?: string | null;
    rejectionReason?: string | null;
    rejectionNotes?: string | null;
    printCount: number;
}

export const getPhlebotomySampleDetail = async (sampleUuid: string): Promise<SpecimenSampleDetail> => {
    const response = await axiosInstance.get(`/api/v1/phlebotomy/samples/${sampleUuid}`);
    return response.data.data as SpecimenSampleDetail;
};

/** Same specimen detail endpoint; allowed for lab reception roles after verification workflows. */
export const getReceptionSampleDetail = getPhlebotomySampleDetail;

export const printSampleLabel = async (sampleId: string) => {
    const response = await axiosInstance.post(`/api/v1/phlebotomy/samples/${sampleId}/print-label`);
    return response.data.data;
};

export const getSupplies = async () => {
    const defaultSupplies = getDefaultSupplies();
    try {
        const response = await axiosInstance.get('/api/v1/supplies');
        const data = response.data?.data ?? [];
        const list = Array.isArray(data) ? data : [];
        if (typeof window !== 'undefined') {
            localStorage.setItem(
                'lims_supplies_cache',
                JSON.stringify(list.length > 0 ? list : defaultSupplies)
            );
        }
        return list.length > 0 ? list : defaultSupplies;
    } catch (err: any) {
        if (typeof window !== 'undefined') {
            const cached = localStorage.getItem('lims_supplies_cache');
            if (cached) return JSON.parse(cached);
            // Seed the cache as the success path does, so a later offline adjustment has rows to work on.
            localStorage.setItem('lims_supplies_cache', JSON.stringify(defaultSupplies));
            return defaultSupplies;
        }
        if (err?.response?.status) throw err;
        return defaultSupplies;
    }
};

export const createSupply = async (data: Record<string, unknown>) => {
    try {
        const response = await axiosInstance.post('/api/v1/supplies', data);
        return response.data.data;
    } catch (err: any) {
        if (typeof window !== 'undefined') {
            const cached = localStorage.getItem('lims_supplies_cache');
            const list = cached ? JSON.parse(cached) : [];
            const newItem = {
                id: crypto.randomUUID(),
                ...data,
                lastRestocked: new Date().toISOString().slice(0, 10),
            };
            const next = [...(Array.isArray(list) ? list : []), newItem];
            localStorage.setItem('lims_supplies_cache', JSON.stringify(next));
            return newItem;
        }
        throw err;
    }
};

export const updateSupply = async (id: string, data: Record<string, unknown>) => {
    try {
        const response = await axiosInstance.patch(`/api/v1/supplies/${id}`, data);
        return response.data.data;
    } catch (err: any) {
        if (typeof window !== 'undefined') {
            const cached = localStorage.getItem('lims_supplies_cache');
            const list = cached ? JSON.parse(cached) : [];
            const next = (Array.isArray(list) ? list : []).map((item: any) =>
                String(item?.id) === id ? { ...item, ...data } : item
            );
            localStorage.setItem('lims_supplies_cache', JSON.stringify(next));
            return next.find((item: any) => String(item?.id) === id);
        }
        throw err;
    }
};

export interface SupplyResponse {
    id: string;
    itemNo?: string;
    name?: string;
    category?: string;
    tubeType?: string;
    tubeColor?: string;
    currentStock: number;
    minStock: number;
    maxStock: number;
    unit?: string;
    lastRestocked?: string;
    version?: number;
}

export const adjustSupplyStock = async (id: string, delta: number): Promise<SupplyResponse> => {
    try {
        const response = await axiosInstance.post(`/api/v1/supplies/${id}/stock-adjustments`, { delta });
        return response.data.data;
    } catch (err: any) {
        // A refusal is the server's answer about the shelf, not a lost connection, so it must reach the caller.
        if (err?.response?.status) throw err;
        if (typeof window !== 'undefined') {
            const cached = localStorage.getItem('lims_supplies_cache');
            const list = cached ? JSON.parse(cached) : [];
            const next = (Array.isArray(list) ? list : []).map((item: any) =>
                String(item?.id) === id
                    ? { ...item, currentStock: Math.max(0, Number(item?.currentStock ?? 0) + delta) }
                    : item
            );
            const adjusted: SupplyResponse | undefined = next.find((item: any) => String(item?.id) === id);
            // Without a cached row there is nothing to move, and a silent undefined would read as a successful refill.
            if (!adjusted) throw new Error('Cannot adjust stock offline: this item is not in the local supplies cache.');
            localStorage.setItem('lims_supplies_cache', JSON.stringify(next));
            return adjusted;
        }
        throw err;
    }
};

export const deleteSupply = async (id: string) => {
    try {
        const response = await axiosInstance.delete(`/api/v1/supplies/${id}`);
        return response.data.data;
    } catch (err: any) {
        if (typeof window !== 'undefined') {
            const cached = localStorage.getItem('lims_supplies_cache');
            const list = cached ? JSON.parse(cached) : [];
            const next = (Array.isArray(list) ? list : []).filter((item: any) => String(item?.id) !== id);
            localStorage.setItem('lims_supplies_cache', JSON.stringify(next));
            return { id };
        }
        throw err;
    }
};

export const getBills = async (page = 0, size = 100) => {
    const response = await axiosInstance.get(`/api/v1/billing/bills?page=${page}&size=${size}`);
    return response.data.data;
};

// Fetches payment history by extracting payments[] from all bills.
// The backend has no standalone /payments endpoint — payments live inside each bill.
export const getPaymentHistory = async (_page = 0, _size = 100) => {
    const candidateEndpoints = [
        `/api/v1/billing/bills?page=0&size=200`,
        `/api/v1/bills?page=0&size=200`,
    ];

    let bills: any[] = [];
    let lastError: any = null;

    for (const endpoint of candidateEndpoints) {
        try {
            const response = await axiosInstance.get(endpoint);
            bills = response.data?.data?.content ?? response.data?.data ?? [];
            break;
        } catch (err: any) {
            lastError = err;
        }
    }

    if (!Array.isArray(bills) || bills.length === 0) {
        const status = lastError?.response?.status;
        if (status === 404) return [];
        if (lastError && status && status !== 404) throw lastError;
        if (lastError && !status) throw lastError;
    }

    // Flatten payments[] out of each bill, enriching each payment with patient info
    const payments: any[] = [];
    for (const bill of bills) {
        const billPayments: any[] = bill.payments ?? [];
        for (const p of billPayments) {
            payments.push({
                id: p.id ?? p.paymentId,
                transactionId: p.transactionId ?? p.id,
                billId: bill.billId ?? bill.id,
                orderId: bill.orderId,
                patientName: bill.patientName,
                patientId: bill.patientId,
                amount: p.amount,
                method: p.paymentMethod ?? p.method,
                status: p.status ?? 'SUCCESS',
                receivedBy: p.receivedBy ?? 'Staff',
                dateTime: p.paymentDate ?? p.createdAt ?? p.date,
                receiptNo: p.receiptNumber ?? p.transactionId ?? p.id,
            });
        }
    }
    return payments;
};

// ===== Admin: Keycloak-backed user management =====
// (Backend endpoints exist only when app.keycloak-admin.enabled=true.)
export interface AdminUser {
    id: string;
    username: string;
    email: string;
    firstName: string;
    lastName: string;
    enabled: boolean;
    branchCode: string;
    roles: string[];
}

export interface CreateAdminUserRequest {
    username: string;
    email: string;
    firstName: string;
    lastName: string;
    role?: string;
    branchCode?: string;
    temporaryPassword?: string;
    enabled?: boolean;
}

export interface UpdateAdminUserRequest {
    firstName?: string;
    lastName?: string;
    email?: string;
    role?: string;
    branchCode?: string;
}

/**
 * Realm roles this admin UI will assign — mirrors the backend's allow-list
 * (AdminUserService.MANAGED_ROLES) and the RBAC table in README.md. SUPER_ADMIN
 * is deliberately left out of self-service create/edit; grant it directly in
 * Keycloak, not from a dropdown.
 */
export const ASSIGNABLE_ROLES: { value: string; label: string }[] = [
    { value: 'MLT', label: 'Medical Laboratory Technician' },
    { value: 'LAB_SUPERVISOR', label: 'Lab Supervisor' },
    { value: 'PATHOLOGIST', label: 'Pathologist' },
    { value: 'PHLEBOTOMIST', label: 'Phlebotomist' },
    { value: 'FRONT_DESK', label: 'Billing / Receptionist' },
    { value: 'DISPATCH', label: 'Dispatch' },
    { value: 'BRANCH_ADMIN', label: 'Branch Admin' },
];

export async function getAdminUsers(): Promise<AdminUser[]> {
    const response = await axiosInstance.get('/api/v1/admin/users');
    return (response.data?.data ?? []) as AdminUser[];
}

export async function createAdminUser(req: CreateAdminUserRequest): Promise<AdminUser> {
    const response = await axiosInstance.post('/api/v1/admin/users', req);
    return (response.data?.data ?? response.data) as AdminUser;
}

export async function updateAdminUser(id: string, req: UpdateAdminUserRequest): Promise<AdminUser> {
    const response = await axiosInstance.put(`/api/v1/admin/users/${id}`, req);
    return (response.data?.data ?? response.data) as AdminUser;
}

export async function setAdminUserEnabled(id: string, value: boolean): Promise<void> {
    await axiosInstance.patch(`/api/v1/admin/users/${id}/enabled`, null, { params: { value } });
}

// ===== Admin: branch directory =====
// Unlike the Keycloak-backed user endpoints above, this is a real, always-on
// table (apps/lims-core-service: com.uom.lims.branch) — no feature flag.
export interface Branch {
    /** Surrogate UUID. Branch-scoped sub-resources (users, tests) are keyed by
     *  this, while the branch record itself is addressed by `code`. */
    id: string;
    code: string;
    name: string;
    location: string | null;
    address: string | null;
    contactEmail: string | null;
    contactPhone: string | null;
    status: "ACTIVE" | "INACTIVE";
    establishedDate: string | null;
    legalEntityName: string | null;
    adminUserId: string | null;
    adminName: string | null;
    adminEmail: string | null;
}

export interface CreateBranchRequest {
    code: string;
    name: string;
    location?: string;
    address?: string;
    contactEmail?: string;
    contactPhone?: string;
    status?: "ACTIVE" | "INACTIVE";
    legalEntityName?: string;
    establishedDate?: string;
}

export interface UpdateBranchRequest {
    name?: string;
    location?: string;
    address?: string;
    contactEmail?: string;
    contactPhone?: string;
    status?: "ACTIVE" | "INACTIVE";
    legalEntityName?: string;
    establishedDate?: string;
}

export async function getBranches(): Promise<Branch[]> {
    const response = await axiosInstance.get('/api/v1/branches');
    return (response.data?.data ?? []) as Branch[];
}

export async function getBranch(code: string): Promise<Branch> {
    const response = await axiosInstance.get(`/api/v1/branches/${code}`);
    return (response.data?.data ?? response.data) as Branch;
}

export async function createBranch(req: CreateBranchRequest): Promise<Branch> {
    const response = await axiosInstance.post('/api/v1/branches', req);
    return (response.data?.data ?? response.data) as Branch;
}

export async function updateBranch(code: string, req: UpdateBranchRequest): Promise<Branch> {
    const response = await axiosInstance.put(`/api/v1/branches/${code}`, req);
    return (response.data?.data ?? response.data) as Branch;
}

export async function assignBranchAdmin(code: string, userId: string, name: string, email: string): Promise<Branch> {
    const response = await axiosInstance.put(`/api/v1/branches/${code}/admin`, { userId, name, email });
    return (response.data?.data ?? response.data) as Branch;
}

// ---------------------------------------------------------------------------
// Critical-value (panic) callbacks
//
// A critical result opens a callback that a clinician must acknowledge with a
// read-back — repeating the value back is what proves it was heard correctly.
// Unacknowledged callbacks escalate on a timer and then auto-close, so the
// worklist below is the only thing standing between a panic value and nobody
// having been told.
// ---------------------------------------------------------------------------

export interface CriticalNotification {
    id: string;
    resultId?: string | null;
    patientCode?: string | null;
    parameterName?: string | null;
    flag?: string | null;
    resultValue?: string | null;
    priority?: string | null;
    status?: string | null;
    escalationLevel?: number | null;
    recipientName?: string | null;
    recipientContact?: string | null;
    channel?: string | null;
    raisedAt?: string | null;
    notifiedAt?: string | null;
    nextEscalationDueAt?: string | null;
    acknowledgedBy?: string | null;
    acknowledgedAt?: string | null;
    readBackText?: string | null;
    communicatedTo?: string | null;
    readBackVerified?: boolean | null;
}

export interface AcknowledgeCriticalRequest {
    /** The value repeated back by the clinician. Required by the backend. */
    readBackText: string;
    /** Who it was communicated to — name and role of the clinician called. */
    communicatedTo?: string;
    readBackVerified?: boolean;
}

export const getOpenCriticalValues = async (limit = 100): Promise<CriticalNotification[]> => {
    const response = await axiosInstance.get('/api/v1/critical-values', { params: { limit } });
    return (response.data ?? []) as CriticalNotification[];
};

export const acknowledgeCriticalValue = async (
    id: string,
    req: AcknowledgeCriticalRequest
): Promise<CriticalNotification> => {
    const response = await axiosInstance.post(`/api/v1/critical-values/${id}/acknowledge`, req);
    return response.data as CriticalNotification;
};

// ---------------------------------------------------------------------------
// QC — instruments and control runs
//
// Internal QC now gates release: a result whose governing control failed, is
// stale, or was never recorded is held at verification. That makes recording a
// control run part of the daily workflow rather than a report nobody reads.
// ---------------------------------------------------------------------------

export interface InstrumentOption {
    code: string;
    name: string;
    instrumentType?: string | null;
    /** False for bench methods — analyser QC does not apply and none can be recorded. */
    qcRequired: boolean;
}

export interface RecordQcRunRequest {
    /** Registry code, not a display name — the gate joins on this. */
    instrument: string;
    /** Human label for the control series, e.g. "Platelets". */
    analyte: string;
    /** Coded analyte; must match a configured test parameter's LOINC. */
    loincCode: string;
    controlLevel: string;
    controlLot?: string;
    measuredValue: number;
    mean: number;
    sd: number;
}

export interface QcRunOutcome {
    id: string;
    status: 'PASS' | 'WARN' | 'FAIL';
    violations: string[];
}

export const getInstrumentRegistry = async (): Promise<InstrumentOption[]> => {
    const response = await axiosInstance.get('/api/v1/mlt/instrument-registry');
    return (response.data ?? []) as InstrumentOption[];
};

export const recordQcRun = async (req: RecordQcRunRequest): Promise<QcRunOutcome> => {
    const response = await axiosInstance.post('/api/v1/mlt/qc-runs', req);
    return response.data as QcRunOutcome;
};

export interface QcAnalyteOption {
    loincCode: string;
    name: string;
}

export const getQcAnalytes = async (): Promise<QcAnalyteOption[]> => {
    const response = await axiosInstance.get('/api/v1/mlt/qc-analytes');
    return (response.data ?? []) as QcAnalyteOption[];
};

// --- Branch Mocks ---

export interface BranchActivityLog {
    id: string;
    timestamp: string;
    performedBy: string;
    entityType: string;
    action: string;
    entityId: string;
    patientCode?: string;
    ipAddress: string;
    details?: string;
}

export const getBranchActivityLogs = async (): Promise<BranchActivityLog[]> => {
    const response = await axiosInstance.get('/api/v1/audit-logs', { params: { size: 100 } });
    return response.data?.content || [];
};

export interface BranchUser {
    id?: string;
    firstName: string;
    lastName: string;
    email: string;
    role: string;
    isActive: boolean;
    lastLogin?: string;
    initials?: string;
    bgColor?: string;
    textColor?: string;
    phone?: string;
    username?: string;
}

export const getBranchUsers = async (branchId: string): Promise<BranchUser[]> => {
    const response = await axiosInstance.get(`/api/v1/branches/${branchId}/users`, {
        params: { size: 100 } // Get all for UI
    });
    return (response.data.content ?? []) as BranchUser[];
};

export const createBranchUser = async (branchId: string, userData: Partial<BranchUser>): Promise<BranchUser> => {
    // Inject branchId into payload
    const payload = { ...userData, branchId };
    const response = await axiosInstance.post(`/api/v1/branches/${branchId}/users`, payload);
    return response.data as BranchUser;
};

export const updateBranchUser = async (userId: string, userData: Partial<BranchUser>): Promise<BranchUser> => {
    const response = await axiosInstance.put(`/api/v1/branch-users/${userId}`, userData);
    return response.data as BranchUser;
};

export const deleteBranchUser = async (userId: string): Promise<void> => {
    await axiosInstance.delete(`/api/v1/branch-users/${userId}`);
};

export interface BranchTest {
    id?: string;
    testName: string;
    testCode: string;
    category: string;
    price: number;
    turnaroundTime: string;
    unit: string;
    referenceRange: string;
    isActive: boolean;
}

export const getBranchTests = async (branchId: string): Promise<BranchTest[]> => {
    const response = await axiosInstance.get(`/api/v1/branches/${branchId}/tests`, {
        params: { size: 100 }
    });
    return (response.data.content ?? []) as BranchTest[];
};

export const createBranchTest = async (branchId: string, testData: Partial<BranchTest>): Promise<BranchTest> => {
    const response = await axiosInstance.post(`/api/v1/branches/${branchId}/tests`, testData);
    return response.data as BranchTest;
};

export const patchBranchTest = async (branchId: string, testId: string, testData: Partial<BranchTest>): Promise<BranchTest> => {
    const response = await axiosInstance.patch(`/api/v1/branches/${branchId}/tests/${testId}`, testData);
    return response.data as BranchTest;
}

// --- Branch Management ---

/** Alias of {@link Branch}: both name the same `BranchService.BranchResponse`
 *  record. Keeping two shapes for one payload let `null` and `undefined` drift
 *  apart, so the branch screens could not pass a branch to each other. */
export type BranchResponse = Branch;

export interface SuperadminUserResponse {
    id: string;
    username: string;
    email: string;
    fullName: string;
    isActive: boolean;
    branchId?: string;
    roles: string[];
}

export interface PageResponseBranch {
    content: BranchResponse[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
    last: boolean;
}

/**
 * BranchController returns the whole directory in one `ApiResponse` envelope —
 * it has no page parameters. Paging here is therefore done on the client so
 * callers still get a stable page shape; `getBranches` is the cheaper call if
 * you want the full list.
 */
export const getBranchesPage = async (page = 0, size = 10): Promise<PageResponseBranch> => {
    const all = await getBranches();
    const start = page * size;
    const content = all.slice(start, start + size);
    const totalPages = size > 0 ? Math.ceil(all.length / size) : 0;
    return {
        content,
        page,
        size,
        totalElements: all.length,
        totalPages,
        last: start + content.length >= all.length,
    };
};

export const createBranchAdmin = async (payload: { code: string; name: string; location?: string; contactEmail?: string; contactPhone?: string; status?: "ACTIVE" | "INACTIVE" }) => {
    const response = await axiosInstance.post('/api/v1/branches', payload);
    return (response.data?.data ?? response.data) as BranchResponse;
};

// Keyed by branch code: BranchController maps PUT /branches/{code}, and
// BranchService resolves it with findByCodeIgnoreCase. Passing a UUID 404s.
export const updateBranchAdmin = async (code: string, payload: { name: string; location?: string; contactEmail?: string; contactPhone?: string; status?: "ACTIVE" | "INACTIVE" }) => {
    const response = await axiosInstance.put(`/api/v1/branches/${code}`, payload);
    return (response.data?.data ?? response.data) as BranchResponse;
};

export const getSuperadminUsers = async () => {
    const response = await axiosInstance.get('/api/v1/superadmin/users');
    return response.data as SuperadminUserResponse[];
};

export const updateSuperadminUser = async (id: string, payload: { email: string; fullName: string; branchId?: string; role?: string; isActive: boolean }) => {
    const response = await axiosInstance.put(`/api/v1/superadmin/users/${id}`, payload);
    return response.data as SuperadminUserResponse;
};

export const resetSuperadminUserPassword = async (id: string, password: string, adminPassword?: string) => {
    const response = await axiosInstance.post(`/api/v1/superadmin/users/${id}/reset-password`, { password, adminPassword });
    return response.data;
};

export const getSuperadminRoles = async () => {
    const response = await axiosInstance.get('/api/v1/superadmin/roles');
    return response.data as string[];
};

export const resetBranchUserPassword = async (id: string, password: string, adminPassword?: string) => {
    const response = await axiosInstance.post(`/api/v1/branch-users/${id}/reset-password`, { password, adminPassword });
    return response.data;
};
