package com.uom.lims.patient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import com.uom.lims.security.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.context.ApplicationEventPublisher;
import com.uom.lims.api.patient.dto.request.PatientCreateRequest;
import com.uom.lims.api.patient.dto.request.PatientUpdateRequest;
import com.uom.lims.api.patient.dto.response.DashboardStatisticsResponse;
import com.uom.lims.api.patient.dto.response.PatientResponse;
import com.uom.lims.api.common.enums.DocumentType;
import com.uom.lims.api.common.enums.IdentityType;
import com.uom.lims.event.PatientDomainEvent;
import com.uom.lims.notification.EmailService;
import com.uom.lims.security.OtpUtils;
import com.uom.lims.security.TokenUtils;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import com.uom.lims.exception.ResourceNotFoundException;
import com.uom.lims.patientdocument.PatientDocumentStorageService;
import com.uom.lims.config.FileStorageProperties;
import com.uom.lims.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PatientService {

        private final PatientRepository patientRepository;
        private final com.uom.lims.patient.validation.PatientValidationService validationService;
        private final com.uom.lims.audit.AuditService auditService;
        private final PatientDocumentStorageService storageService;
        private final FileStorageProperties fileStorageProperties;
        private final EmailService emailService;
        private final com.uom.lims.notification.SmsService smsService;
        private final ApplicationEventPublisher applicationEventPublisher;
        private final com.uom.lims.config.LabTimeZone labTimeZone;

        public PatientResponse registerPatient(PatientCreateRequest request, String ipAddress) {
                // Check duplicate phone
                validationService.validatePhoneUnique(request.getPhone(), null);

                // Check duplicate email (only if provided)
                validationService.validateEmailUnique(request.getEmail(), null);

                // Check duplicate identity number (only if provided)
                validationService.validateIdentityUnique(request.getIdentityType(), request.getIdentityNumber(), null);

                // Generate patient code
                String patientCode = generatePatientCode();

                // Map to entity
                PatientEntity patient = new PatientEntity();
                patient.setPatientCode(patientCode);
                patient.setTitle(request.getTitle());
                patient.setFullName(request.getFullName());
                patient.setDob(request.getDob());
                patient.setGender(request.getGender());
                patient.setMaritalStatus(request.getMaritalStatus());
                patient.setNationality(request.getNationality());
                patient.setBloodGroup(request.getBloodGroup());
                patient.setIdentityType(request.getIdentityType());
                patient.setIdentityNumber(request.getIdentityNumber());
                patient.setPhone(request.getPhone());
                patient.setEmail(request.getEmail());
                patient.setHomeNumber(request.getHomeNumber());
                patient.setAddress(request.getAddress());
                patient.setContactPersonPhone(request.getContactPersonPhone());

                // Branch is derived from the authenticated user so a branch user
                // can only register patients into their own branch. A SUPER_ADMIN
                // may register into an explicitly requested branch.
                String branchCode = (SecurityUtils.isSuperAdmin()
                                && request.getBranchCode() != null
                                && !request.getBranchCode().isBlank())
                                                ? request.getBranchCode()
                                                : SecurityUtils.getCurrentBranchId();
                patient.setBranchCode(branchCode);

                // Save
                PatientEntity saved = patientRepository.save(patient);

                initiateEmailVerification(saved);

                // Audit Log (inside transaction)
                auditService.log(
                                "REGISTER_PATIENT",
                                "PATIENT",
                                saved.getId(),
                                saved.getPatientCode(),
                                String.format("{\"name\":\"%s\"}", saved.getFullName()),
                                ipAddress);

                applicationEventPublisher.publishEvent(new PatientDomainEvent(
                                "PATIENT_REGISTERED",
                                saved.getPatientCode(),
                                saved.getEmail(),
                                saved.getPhone(),
                                LocalDateTime.now()));

                return mapToPatientResponse(saved);
        }

        private String generatePatientCode() {

                Long sequenceValue = patientRepository.getNextPatientSequence();

                String year = String.valueOf(java.time.Year.now().getValue());

                return "PAT" + year + "-" + String.format("%05d", sequenceValue);
        }

        public PatientResponse getPatientByCode(String patientCode) {

                PatientEntity patient = patientRepository.findByPatientCode(patientCode)
                                .orElse(null);

                if (patient == null) {
                        if (SecurityUtils.hasRole("PATIENT")) {
                                org.springframework.security.core.Authentication authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                                if (authentication != null && authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                                        String currentUserCode = jwt.getClaimAsString("preferred_username");
                                        if (patientCode.equals(currentUserCode)) {
                                                // Just-In-Time Mock Profile for unlinked Keycloak testing accounts
                                                PatientResponse mockResponse = new PatientResponse();
                                                mockResponse.setPatientCode(patientCode);
                                                mockResponse.setFullName(jwt.getClaimAsString("name") != null ? jwt.getClaimAsString("name") : "Test User");
                                                mockResponse.setEmail(jwt.getClaimAsString("email"));
                                                mockResponse.setPhone("0770000000");
                                                mockResponse.setGender(com.uom.lims.api.common.enums.Gender.MALE);
                                                mockResponse.setDob(java.time.LocalDate.of(1990, 1, 1));
                                                return mockResponse;
                                        }
                                }
                        }
                        throw new ResourceNotFoundException("Patient not found with code: " + patientCode);
                }

                // A patient is a hospital-wide record, not a branch-owned one: someone
                // registered at Colombo must be servable at Kandy without re-registering.
                // Reading demographics is therefore allowed from any branch — but the
                // access is recorded, because a cross-branch read is a PHI access the
                // patient's home branch has no other way to see.
                //
                // Branch isolation still holds everywhere it matters: writes go through
                // assertCanAccessBranch (see updatePatientProfile), and orders, samples,
                // results and dashboards remain scoped to the branch that created them.
                if (SecurityUtils.isAuthenticated()
                                && !SecurityUtils.canAccessBranch(patient.getBranchCode())) {
                        recordCrossBranchAccess(patient);
                }

                return mapToPatientResponse(patient);
        }

        /**
         * Audit a read of a patient owned by another branch. Kept separate from the
         * read itself so the audit write cannot change what the caller sees: an audit
         * outage must not deny clinical staff access to a patient in front of them.
         */
        private void recordCrossBranchAccess(PatientEntity patient) {
                String callerBranch = SecurityUtils.getCurrentBranchId();
                log.info("Cross-branch patient read: user '{}' (branch {}) read patient {} owned by branch {}",
                                SecurityUtils.getCurrentUsername(), callerBranch, patient.getPatientCode(),
                                patient.getBranchCode());
                try {
                        // writeStandalone (REQUIRES_NEW), not log (MANDATORY): a failed
                        // audit insert must not mark the caller's transaction rollback-only.
                        auditService.writeStandalone(
                                        "PATIENT_CROSS_BRANCH_ACCESS",
                                        "PATIENT",
                                        patient.getId(),
                                        patient.getPatientCode(),
                                        String.format("{\"homeBranch\":\"%s\",\"accessedFromBranch\":\"%s\"}",
                                                        patient.getBranchCode(), callerBranch),
                                        null);
                } catch (RuntimeException e) {
                        log.warn("Failed to audit cross-branch read of patient {}: {}",
                                        patient.getPatientCode(), e.toString());
                }
        }

        public Page<PatientResponse> searchPatients(
                        String keyword,
                        int page,
                        int size,
                        String sortBy,
                        String direction) {

                Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending()
                                : Sort.by(sortBy).ascending();

                Pageable pageable = PageRequest.of(page, size, sort);

                // Browsing and searching are deliberately scoped differently.
                //
                // No keyword is a browse of "my branch's patients" — the register the
                // front desk expects to see, and what the branch dashboard counts. Left
                // unscoped it would instead dump every branch's patient list, which is
                // both useless at the desk and a needless PHI exposure.
                //
                // A keyword is a deliberate lookup of a named person, which is exactly
                // the case the branches need: a patient registered at one branch must be
                // findable — and servable — at any other without re-registration.
                boolean isKeywordSearch = keyword != null && !keyword.isBlank();
                String branchScope = isKeywordSearch ? null : SecurityUtils.resolveBranchScope();

                Specification<PatientEntity> specification = PatientSpecification.keywordInBranch(
                                keyword, branchScope);

                Page<PatientEntity> patients = patientRepository.findAll(specification, pageable);

                return patients.map(this::mapToPatientResponse);
        }

        public Page<PatientResponse> advancedSearchPatients(
                        String fullName,
                        String phone,
                        String identityNumber,
                        String email,
                        String branchCode,
                        Boolean phoneVerified,
                        Boolean emailVerified,
                        int page,
                        int size,
                        String sortBy,
                        String direction) {

                Sort sort = direction.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending()
                                : Sort.by(sortBy).ascending();

                Pageable pageable = PageRequest.of(page, size, sort);

                // Same rule as the keyword search, applied to the structured filters:
                // an identifying filter means the caller is looking for a specific
                // person, so the search spans all branches and any supplied branchCode
                // only narrows it further.
                //
                // Without one of those filters this is a bulk listing, so it stays
                // pinned to the caller's own branch. Otherwise `?branchCode=B002` with
                // no other filter would hand any branch user another branch's entire
                // patient register — enumeration, not a patient lookup.
                boolean isIdentifyingSearch = isNotBlank(fullName)
                                || isNotBlank(phone)
                                || isNotBlank(identityNumber)
                                || isNotBlank(email);

                String effectiveBranch;
                if (isIdentifyingSearch) {
                        effectiveBranch = branchCode;
                } else {
                        String scope = SecurityUtils.resolveBranchScope(); // null => SUPER_ADMIN
                        effectiveBranch = (scope == null) ? branchCode : scope;
                }

                Specification<PatientEntity> specification = PatientSpecification.filterPatients(
                                fullName,
                                phone,
                                identityNumber,
                                email,
                                effectiveBranch,
                                phoneVerified,
                                emailVerified);

                Page<PatientEntity> patients = patientRepository.findAll(specification, pageable);

                return patients.map(this::mapToPatientResponse);
        }

        private static boolean isNotBlank(String value) {
                return value != null && !value.isBlank();
        }

        @Transactional
        @CacheEvict(value = "profilePhotoUrl", key = "#patientCode")
        public String updateProfilePhoto(String patientCode, MultipartFile file, String ipAddress)
                        throws java.io.IOException {
                // 1. Validate
                validateProfilePhoto(file);

                // 2. Find the patient
                PatientEntity patient = patientRepository.findByPatientCode(patientCode)
                                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientCode));

                // 3. If an old photo exists, delete it from S3
                if (patient.getProfilePhotoPath() != null) {
                        storageService.deleteFile(patient.getProfilePhotoPath());
                }

                // 4. Upload the new photo
                String newPath = storageService.uploadFile(patientCode, DocumentType.PROFILE_PHOTO, file);

                // 5. Update the entity
                String oldPath = patient.getProfilePhotoPath();
                patient.setProfilePhotoPath(newPath);
                patientRepository.save(patient);

                // 6. Audit Log
                auditService.log(
                                "UPDATE_PROFILE_PHOTO",
                                "PATIENT",
                                patient.getId(),
                                patient.getPatientCode(),
                                String.format("{\"oldPath\":\"%s\", \"newPath\":\"%s\"}",
                                                oldPath, newPath),
                                ipAddress);

                return newPath;
        }

        private void validateProfilePhoto(MultipartFile file) {
                if (file.isEmpty()) {
                        throw new InvalidRequestException("File cannot be empty");
                }

                if (file.getSize() > fileStorageProperties.getMaxSize()) {
                        throw new InvalidRequestException("File size exceeds limit");
                }

                String contentType = file.getContentType();
                if (contentType == null || !fileStorageProperties.getAllowedTypes().contains(contentType)) {
                        throw new InvalidRequestException(
                                        "Invalid file type. Allowed: " + fileStorageProperties.getAllowedTypes());
                }
        }

        @Transactional(readOnly = true)
        @Cacheable(value = "profilePhotoUrl", key = "#patientCode")
        public String getProfilePhotoUrl(String patientCode) {

                PatientEntity patient = patientRepository.findByPatientCode(patientCode)
                                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientCode));

                String photoPath = patient.getProfilePhotoPath();

                if (photoPath == null || photoPath.isBlank()) {
                        throw new ResourceNotFoundException("Profile photo not found for patient: " + patientCode);
                }
                return storageService.generatePresignedUrl(photoPath, Duration.ofMinutes(10));
        }

        @Transactional
        public PatientResponse updatePatientProfile(
                        String patientCode,
                        PatientUpdateRequest request,
                        String ipAddress) {

                // 1. Fetch Patient. Do not invent a row here: the previous JIT mock
                // constructed an unsaved entity and then patientRepository.save()
                // inserted it as a real patient with a hardcoded gender and DOB.
                PatientEntity patient = patientRepository.findByPatientCode(patientCode)
                                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientCode));

                // Tenant isolation on the write path. getPatientByCode() already
                // guards the read; without the same guard here a branch user could
                // load nothing and still mutate another branch's patient by code.
                SecurityUtils.assertCanAccessBranch(patient.getBranchCode(), "Patient", patientCode);

                // 2. Validate Phone Uniqueness (if changed)
                if (request.getPhone() != null && !patient.getPhone().equals(request.getPhone())) {
                        validationService.validatePhoneUnique(request.getPhone(), patientCode);
                        // Reset verification if phone changed
                        patient.setPhoneVerified(false);
                }

                // 3. Validate Identity Uniqueness (if changed)
                if (request.getIdentityNumber() != null || request.getIdentityType() != null) {
                        String newIdNum = request.getIdentityNumber() != null ? request.getIdentityNumber()
                                        : patient.getIdentityNumber();
                        IdentityType newIdType = request.getIdentityType() != null ? request.getIdentityType()
                                        : patient.getIdentityType();

                        if (!newIdNum.equals(patient.getIdentityNumber())
                                        || !newIdType.equals(patient.getIdentityType())) {
                                validationService.validateIdentityUnique(newIdType, newIdNum, patientCode);
                        }
                }

                // 4. Capture Old State for Audit
                String oldState = String.format("Phone: %s, Identity: %s", patient.getPhone(),
                                patient.getIdentityNumber());

                // Set version for optimistic locking
                // if (request.getVersion() != null) {
                // patient.setVersion(request.getVersion());
                // }

                // 5. Update Fields
                if (request.getTitle() != null)
                        patient.setTitle(request.getTitle());
                if (request.getFullName() != null)
                        patient.setFullName(request.getFullName());
                if (request.getDob() != null)
                        patient.setDob(request.getDob());
                if (request.getGender() != null)
                        patient.setGender(request.getGender());
                if (request.getMaritalStatus() != null)
                        patient.setMaritalStatus(request.getMaritalStatus());
                if (request.getNationality() != null)
                        patient.setNationality(request.getNationality());
                if (request.getBloodGroup() != null)
                        patient.setBloodGroup(request.getBloodGroup());
                if (request.getIdentityType() != null)
                        patient.setIdentityType(request.getIdentityType());
                if (request.getIdentityNumber() != null)
                        patient.setIdentityNumber(request.getIdentityNumber());
                if (request.getPhone() != null)
                        patient.setPhone(request.getPhone());
                if (request.getHomeNumber() != null)
                        patient.setHomeNumber(request.getHomeNumber());
                if (request.getAddress() != null)
                        patient.setAddress(request.getAddress());
                if (request.getContactPersonName() != null)
                        patient.setContactPersonName(request.getContactPersonName());
                if (request.getContactPersonPhone() != null)
                        patient.setContactPersonPhone(request.getContactPersonPhone());
                // Branch is NOT mass-assignable. Accepting request.getBranchCode()
                // here let any branch user silently move a patient — and that
                // patient's whole order/sample/result history — into another
                // branch, or out of their own so colleagues could no longer see
                // them. Only a SUPER_ADMIN may reassign, mirroring registration
                // above.
                if (request.getBranchCode() != null
                                && !request.getBranchCode().isBlank()
                                && !request.getBranchCode().equalsIgnoreCase(patient.getBranchCode())) {
                        if (!SecurityUtils.isSuperAdmin()) {
                                throw new AccessDeniedException(
                                                "Only a super administrator may move a patient between branches");
                        }
                        patient.setBranchCode(request.getBranchCode());
                }

                // 6. Reset Email Verification if changed
                boolean emailChanged = false;
                if (request.getEmail() != null && !request.getEmail().equals(patient.getEmail())) {
                        validationService.validateEmailUnique(request.getEmail(), patientCode);
                        patient.setEmail(request.getEmail());
                        emailChanged = true;
                }

                if (emailChanged) {
                        initiateEmailVerification(patient);
                }

                // 7. Save
                patientRepository.save(patient);

                applicationEventPublisher.publishEvent(new PatientDomainEvent(
                                "PATIENT_PROFILE_UPDATED",
                                patient.getPatientCode(),
                                patient.getEmail(),
                                patient.getPhone(),
                                LocalDateTime.now()));

                // 8. Audit
                auditService.log(
                                "UPDATE_PROFILE",
                                "PATIENT",
                                patient.getId(),
                                patient.getPatientCode(),
                                String.format("{\"old_state\":\"%s\", \"new_phone\":\"%s\"}", oldState,
                                                request.getPhone()),
                                ipAddress);

                return mapToPatientResponse(patient);
        }

        public DashboardStatisticsResponse getDashboardStatistics(String branchCode) {
                // Counted against the laboratory's day. On the UTC host "today" began at
                // 05:30 local, so everything registered before breakfast fell into yesterday.
                ZoneId zone = labTimeZone.zone();
                LocalDateTime now = LocalDateTime.now(zone);
                LocalDateTime beginningOfToday = now.toLocalDate().atStartOfDay();
                LocalDateTime beginningOfWeek = beginningOfToday.minusDays(now.getDayOfWeek().getValue() % 7);
                Instant beginningOfTodayInstant = beginningOfToday.atZone(zone).toInstant();
                Instant beginningOfWeekInstant = beginningOfWeek.atZone(zone).toInstant();

                long todayCount;
                long weekCount;
                long pendingVerifications;

                if (branchCode != null && !branchCode.isEmpty()) {
                        todayCount = patientRepository.countByBranchCodeAndCreatedAtAfter(branchCode,
                                        beginningOfTodayInstant);
                        weekCount = patientRepository.countByBranchCodeAndCreatedAtAfter(branchCode,
                                        beginningOfWeekInstant);
                        pendingVerifications = patientRepository
                                        .countByBranchCodeAndEmailVerifiedFalseAndPhoneVerifiedFalse(branchCode);
                } else {
                        todayCount = patientRepository.countByCreatedAtAfter(beginningOfTodayInstant);
                        weekCount = patientRepository.countByCreatedAtAfter(beginningOfWeekInstant);
                        pendingVerifications = patientRepository.countByEmailVerifiedFalseAndPhoneVerifiedFalse();
                }

                // Real trend: registrations since start-of-yesterday minus today's = yesterday's.
                Instant beginningOfYesterdayInstant = beginningOfToday.minusDays(1).atZone(zone).toInstant();
                long sinceYesterday = (branchCode != null && !branchCode.isEmpty())
                                ? patientRepository.countByBranchCodeAndCreatedAtAfter(branchCode,
                                                beginningOfYesterdayInstant)
                                : patientRepository.countByCreatedAtAfter(beginningOfYesterdayInstant);
                long yesterdayCount = Math.max(0, sinceYesterday - todayCount);

                return DashboardStatisticsResponse.builder()
                                .patientsRegisteredToday(todayCount)
                                .newPatientsThisWeek(weekCount)
                                .pendingVerifications(pendingVerifications)
                                .todayTrend(formatTrend(todayCount, yesterdayCount))
                                .build();
        }

        private static String formatTrend(long today, long yesterday) {
                if (yesterday == 0) {
                        return today == 0 ? "no change vs yesterday" : "+100% vs yesterday";
                }
                long pct = Math.round((today - yesterday) * 100.0 / yesterday);
                return (pct >= 0 ? "+" : "") + pct + "% vs yesterday";
        }

        private void initiateEmailVerification(PatientEntity patient) {

                if (patient.getEmail() == null || patient.getEmail().isBlank()) {
                        return;
                }

                String rawToken = TokenUtils.generateRawToken();
                String hashedToken = TokenUtils.hashToken(rawToken);

                patient.setEmailVerified(false);
                patient.setEmailVerificationTokenHash(hashedToken);
                patient.setEmailVerificationExpiry(LocalDateTime.now().plusHours(24));
                patient.setLastVerificationSentAt(LocalDateTime.now());

                // Send after commit (raw token lives only in the event, not the DB).
                applicationEventPublisher.publishEvent(
                                new com.uom.lims.notification.EmailVerificationRequestedEvent(
                                                patient.getEmail(), patient.getFullName(), rawToken));
        }

        @Transactional
        public boolean verifyEmail(String rawToken, String ipAddress) {

                String hashedToken = TokenUtils.hashToken(rawToken);

                PatientEntity patient = patientRepository
                                .findByEmailVerificationTokenHash(hashedToken)
                                .orElse(null);

                if (patient == null) {
                        return false;
                }

                if (patient.getEmailVerificationExpiry() == null ||
                                patient.getEmailVerificationExpiry().isBefore(LocalDateTime.now())) {
                        return false;
                }

                patient.setEmailVerified(true);
                patient.setEmailVerificationTokenHash(null);
                patient.setEmailVerificationExpiry(null);

                applicationEventPublisher.publishEvent(new PatientDomainEvent(
                                "PATIENT_EMAIL_VERIFIED",
                                patient.getPatientCode(),
                                patient.getEmail(),
                                patient.getPhone(),
                                LocalDateTime.now()));

                auditService.log(
                                "EMAIL_VERIFIED",
                                "PATIENT",
                                patient.getId(),
                                patient.getPatientCode(),
                                "{\"message\": \"Email verified successfully\"}",
                                ipAddress);

                return true;
        }

        @Transactional
        public void resendEmailVerification(String patientCode, String ipAddress) {

                PatientEntity patient = patientRepository.findByPatientCode(patientCode)
                                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientCode));

                if (Boolean.TRUE.equals(patient.isEmailVerified())) {
                        throw new InvalidRequestException("Email already verified");
                }

                if (patient.getEmail() == null || patient.getEmail().isBlank()) {
                        throw new InvalidRequestException("Patient has no email address");
                }

                if (patient.getLastVerificationSentAt() != null &&
                                patient.getLastVerificationSentAt().isAfter(LocalDateTime.now().minusSeconds(60))) {
                        throw new InvalidRequestException("Please wait before resending verification email");
                }

                // Daily Limit Check
                java.time.LocalDate today = java.time.LocalDate.now();
                if (patient.getVerificationResendDate() == null || !patient.getVerificationResendDate().equals(today)) {
                        patient.setVerificationResendCount(0);
                        patient.setVerificationResendDate(today);
                }

                if (patient.getVerificationResendCount() >= 5) {
                        throw new InvalidRequestException("Daily verification resend limit exceeded");
                }

                // Increment count
                patient.setVerificationResendCount(patient.getVerificationResendCount() + 1);

                // Reuse existing logic matches user request
                initiateEmailVerification(patient);
                patientRepository.save(patient);

                auditService.log(
                                "RESEND_EMAIL_VERIFICATION",
                                "PATIENT",
                                patient.getId(),
                                patient.getPatientCode(),
                                "{\"message\":\"Verification email resent\"}",
                                ipAddress);
        }

        @Transactional
        public void sendPhoneOtp(String patientCode, String ipAddress) {

                PatientEntity patient = patientRepository.findByPatientCode(patientCode)
                                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientCode));

                if (patient.isPhoneVerified()) {
                        throw new InvalidRequestException("Phone already verified");
                }

                if (patient.getLastOtpSentAt() != null &&
                                patient.getLastOtpSentAt().isAfter(LocalDateTime.now().minusSeconds(60))) {
                        throw new InvalidRequestException("Please wait before requesting another OTP");
                }

                if (patient.getPhoneResendDate() == null
                                || !patient.getPhoneResendDate().equals(java.time.LocalDate.now())) {
                        patient.setPhoneResendCount(0);
                        patient.setPhoneResendDate(java.time.LocalDate.now());
                }

                if (patient.getPhoneResendCount() >= 5) {
                        throw new InvalidRequestException("Daily SMS limit exceeded. Please try again tomorrow.");
                }

                String rawOtp = OtpUtils.generateOtp();
                String hashedOtp = TokenUtils.hashToken(rawOtp);

                patient.setPhoneOtpHash(hashedOtp);
                patient.setPhoneOtpExpiry(LocalDateTime.now().plusMinutes(5));
                patient.setPhoneOtpAttempts(0);
                patient.setLastOtpSentAt(LocalDateTime.now());
                patient.setPhoneResendCount(patient.getPhoneResendCount() + 1);

                patientRepository.save(patient);

                // Send after commit so a rollback never leaves a live OTP for an
                // uncommitted state, and SMS latency never pins the transaction.
                applicationEventPublisher.publishEvent(
                                new com.uom.lims.notification.PhoneOtpRequestedEvent(patient.getPhone(), rawOtp));

                auditService.log(
                                "SEND_PHONE_OTP",
                                "PATIENT",
                                patient.getId(),
                                patient.getPatientCode(),
                                "{\"message\":\"Phone OTP sent\"}",
                                ipAddress);
        }

        @Transactional
        public void verifyPhoneOtp(String patientCode, String rawOtp, String ipAddress) {

                PatientEntity patient = patientRepository.findByPatientCode(patientCode)
                                .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientCode));

                if (patient.getPhoneOtpExpiry() == null ||
                                patient.getPhoneOtpExpiry().isBefore(LocalDateTime.now())) {
                        // Expected user condition — not an error; no debug audit/log noise.
                        throw new InvalidRequestException("OTP expired");
                }

                if (patient.getPhoneOtpAttempts() >= 5) {
                        throw new InvalidRequestException("Too many invalid attempts");
                }

                String hashedOtp = TokenUtils.hashToken(rawOtp);

                if (!hashedOtp.equals(patient.getPhoneOtpHash())) {

                        patient.setPhoneOtpAttempts(patient.getPhoneOtpAttempts() + 1);
                        patientRepository.save(patient);

                        throw new InvalidRequestException("Invalid OTP");
                }

                patient.setPhoneVerified(true);
                patient.setPhoneOtpHash(null);
                patient.setPhoneOtpExpiry(null);
                patient.setPhoneOtpAttempts(0);
                patientRepository.save(patient);

                applicationEventPublisher.publishEvent(new PatientDomainEvent(
                                "PATIENT_PHONE_VERIFIED",
                                patient.getPatientCode(),
                                patient.getEmail(),
                                patient.getPhone(),
                                LocalDateTime.now()));

                auditService.log(
                                "PHONE_VERIFIED",
                                "PATIENT",
                                patient.getId(),
                                patient.getPatientCode(),
                                "{\"message\":\"Phone verified successfully\"}",
                                ipAddress);
        }

        private PatientResponse mapToPatientResponse(PatientEntity patient) {
                return PatientResponse.builder()
                                .patientCode(patient.getPatientCode())
                                .title(patient.getTitle())
                                .fullName(patient.getFullName())
                                .email(patient.getEmail())
                                .phone(patient.getPhone())
                                .phoneVerified(patient.isPhoneVerified())
                                .emailVerified(patient.isEmailVerified())
                                .createdAt(toLocalDateTime(patient.getCreatedAt()))
                                .updatedAt(toLocalDateTime(patient.getLastModifiedAt()))
                                .profilePhotoUrl(resolveProfilePhotoUrl(patient))
                                .address(patient.getAddress())
                                .dob(patient.getDob())
                                .gender(patient.getGender())
                                .maritalStatus(patient.getMaritalStatus())
                                .nationality(patient.getNationality())
                                .bloodGroup(patient.getBloodGroup())
                                .identityType(patient.getIdentityType())
                                .identityNumber(patient.getIdentityNumber())
                                .homeNumber(patient.getHomeNumber())
                                .branchCode(patient.getBranchCode())
                                .contactPersonName(patient.getContactPersonName())
                                .contactPersonPhone(patient.getContactPersonPhone())
                                .build();
        }

        /**
         * A presigned photo URL is a nicety; the patient record is not. When document
         * storage is down (S3 circuit open, presigner misconfigured) every patient list
         * and the reception dashboard used to 500 because this call threw inside the
         * response mapper. Degrade to no photo instead and keep the list serving.
         */
        private String resolveProfilePhotoUrl(PatientEntity patient) {
                if (patient.getProfilePhotoPath() == null || patient.getProfilePhotoPath().isBlank()) {
                        return null;
                }
                try {
                        return storageService.generatePresignedUrl(
                                        patient.getProfilePhotoPath(), java.time.Duration.ofMinutes(10));
                } catch (RuntimeException ex) {
                        log.warn("Profile photo URL unavailable for {} — serving record without photo: {}",
                                        patient.getPatientCode(), ex.getMessage());
                        return null;
                }
        }

        /**
         * The wall-clock time the laboratory saw, which is what "Registered 18:01"
         * on the patient list means. Read through the container's zone instead, a
         * UTC host reported every registration 5:30 early.
         */
        private LocalDateTime toLocalDateTime(Instant instant) {
                return instant == null ? null : LocalDateTime.ofInstant(instant, labTimeZone.zone());
        }
}
