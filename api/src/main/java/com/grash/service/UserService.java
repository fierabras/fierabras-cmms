package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.dto.LdapLoginRequest;
import com.grash.dto.SignupSuccessResponse;
import com.grash.dto.SuccessResponse;
import com.grash.dto.UserInvitationDTO;
import com.grash.dto.UserPatchDTO;
import com.grash.dto.UserSignupRequest;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.dto.license.LicensingState;
import com.grash.event.CompanyCreatedEvent;
import com.grash.exception.ConflictException;
import com.grash.exception.CustomException;
import com.grash.factory.MailServiceFactory;
import com.grash.mapper.UserMapper;
import com.grash.model.*;
import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.repository.UserRepository;
import com.grash.repository.VerificationTokenRepository;
import com.grash.repository.WorkOrderRepository;
import com.grash.security.CustomUserDetail;
import com.grash.security.JwtTokenProvider;
import com.grash.utils.AfterCommit;
import com.grash.utils.Helper;
import jakarta.mail.MessagingException;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import javax.naming.directory.SearchControls;

import static com.grash.utils.Consts.usageBasedLicenseLimits;


@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final EntityManager em;
    private final AuthenticationManager authenticationManager;
    private final MessageSource messageSource;
    private final MailServiceFactory mailServiceFactory;
    private final ExternalSendService externalSendService;
    private final RoleService roleService;
    private final CompanyService companyService;
    private final CurrencyService currencyService;
    private final UserInvitationService userInvitationService;
    private final VerificationTokenRepository verificationTokenRepository;
    private final SubscriptionPlanService subscriptionPlanService;
    private final SubscriptionService subscriptionService;
    private final UserMapper userMapper;
    private final BrandingService brandingService;
    private final DemoDataService demoDataService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final LicenseService licenseService;
    private final CacheService cacheService;
    private final IntercomService intercomService;
    private final AuditLogService auditLogService;
    private final DeletionService deletionService;
    private final WorkOrderRepository workOrderRepository;

    /**
     * P-11: the owner tried to delete or disable their own account (PPB-10, PPB-12).
     */
    public static final String OWNER_ACCOUNT = "OWNER_ACCOUNT";
    private static final String OWNER_ACCOUNT_MESSAGE = "The owner of the company cannot delete or disable their "
            + "own account";
    /**
     * P-11: a disabled user was given as a new reference (PPB-08).
     */
    public static final String USER_RETIRED = "USER_RETIRED";
    private static final String USER_RETIRED_MESSAGE = "This user is disabled and cannot be assigned";
    public static final String USER_IN_USE = "USER_IN_USE";
    private static final String USER_IN_USE_MESSAGE = "This user is used by other records. Disable it instead of "
            + "deleting it";

    @Value("${api.host}")
    private String PUBLIC_API_URL;
    @Value("${frontend.url}")
    private String frontendUrl;
    @Value("${mail.recipients:#{null}}")
    private String[] recipients;
    @Value("${security.invitation-via-email}")
    private boolean enableInvitationViaEmail;
    @Value("${mail.enable}")
    private boolean enableMails;
    @Value("${cloud-version}")
    private boolean cloudVersion;
    @Value("${allowed-organization-admins}")
    private String[] allowedOrganizationAdmins;

    public String signin(String email, String password, String type) {
        try {
            cacheService.evictUserFromCache(email);
            Authentication authentication =
                    authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
            if (authentication.getAuthorities().stream().noneMatch(grantedAuthority -> grantedAuthority.getAuthority().equals("ROLE_" + type.toUpperCase()))) {
                throw new CustomException("Invalid credentials", HttpStatus.FORBIDDEN);
            }
            Optional<User> optionalUser = userRepository.findByEmailIgnoreCase(email);
            User user = optionalUser.get();
            user.setLastLogin(new Date());
            userRepository.save(user);
            return jwtTokenProvider.createToken(email, Collections.singletonList(user.getRole().getRoleType()));
        } catch (AuthenticationException e) {
            throw new CustomException("Invalid credentials", HttpStatus.FORBIDDEN);
        }
    }


    private void onCompanyAndUserCreation(User user) {
        if (cloudVersion && user.isOwnsCompany()) {
            applicationEventPublisher.publishEvent(new CompanyCreatedEvent(user));
        }
    }

    private SignupSuccessResponse<User> enableAndReturnToken(User user, boolean sendEmailToSuperAdmins,
                                                             UserSignupRequest userSignupRequest) {
        user.setEnabled(true);
        userRepository.save(user);
        if (sendEmailToSuperAdmins)
            sendRegistrationMailToSuperAdmins(user, userSignupRequest);
        onCompanyAndUserCreation(user);
        return new SignupSuccessResponse<>(true, jwtTokenProvider.createToken(user.getEmail(),
                Collections.singletonList(user.getRole().getRoleType())), user);
    }

    public void checkUsageBasedLimit(int newUsersCount) {
        LicensingState licensingState = licenseService.getLicensingState();
        if (licensingState.isHasLicense()) {
            if (userRepository.hasMorePaidUsersThan(licensingState.getUsersCount() - newUsersCount))
                throw new RuntimeException("Cannot create more users than the license allows: " + licensingState.getUsersCount() + ". Refer to https://github.com/Grashjs/cmms/blob/main/dev-docs/Disable%20users.md");
        }
        Integer threshold = usageBasedLicenseLimits.get(LicenseEntitlement.UNLIMITED_USERS);
        if (!licenseService.hasEntitlement(LicenseEntitlement.UNLIMITED_USERS)
                && userRepository.hasMorePaidUsersThan(threshold - newUsersCount
        ))
            throw new RuntimeException("Cannot create more users than the free license allows: " + threshold + ". " +
                    "Refer to" +
                    " https://github.com/Grashjs/cmms/blob/main/dev-docs/Disable%20users.md");
    }

    public SignupSuccessResponse<User> signup(UserSignupRequest userReq) {
        User user = userMapper.toModel(userReq);
        user.setEmail(user.getEmail().toLowerCase());
        if (userRepository.existsByEmailIgnoreCase(user.getEmail())) {
            throw new CustomException("Email is already in use", HttpStatus.UNPROCESSABLE_ENTITY);

        }
        if (allowedOrganizationAdmins != null && userReq.getRole() == null && allowedOrganizationAdmins.length != 0 && Arrays.stream(allowedOrganizationAdmins).noneMatch(allowedOrganizationAdmin -> allowedOrganizationAdmin.equalsIgnoreCase(userReq.getEmail()))) {
            throw new CustomException("You are not allowed to create an account without being invited",
                    HttpStatus.NOT_ACCEPTABLE);
        }
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setUsername(Helper.generateStringId());
        if (user.getRole() == null) {
            //create company with default roles
            if (!licenseService.hasEntitlement(LicenseEntitlement.MULTI_INSTANCE) && companyService.existsAtLeastOneWithMinWorkOrders())
                throw new CustomException("You need a license to create another company", HttpStatus.FORBIDDEN);
            Subscription subscription =
                    Subscription.builder().usersCount(300).monthly(cloudVersion)
                            .startsOn(new Date())
                            .endsOn(cloudVersion ? Helper.incrementDays(new Date(), 15) : null)
                            .subscriptionPlan(subscriptionPlanService.findByCode("BUSINESS").get()).build();
            subscriptionService.create(subscription);
            Company company = new Company(userReq.getCompanyName(), userReq.getEmployeesCount(), subscription);
            company.setDemo(Boolean.TRUE.equals(userReq.getDemo()));
            company.getCompanySettings().getGeneralPreferences().setCurrency(currencyService.findByCode("$").get());
            if (userReq.getLanguage() != null)
                company.getCompanySettings().getGeneralPreferences().setLanguage(userReq.getLanguage());
            if (userReq.getTimeZone() != null)
                company.getCompanySettings().getGeneralPreferences().setTimeZone(userReq.getTimeZone());
            companyService.create(company);
            user.setOwnsCompany(true);
            user.setCompany(company);
            user.setRole(roleService.findDefaultRoleWithCode(RoleCode.ADMIN).get());
            checkUsageBasedLimit(1);
        } else {
            Role role = roleService.findById(user.getRole().getId()).orElseThrow(() -> new CustomException("Role not " +
                    "found", HttpStatus.NOT_ACCEPTABLE));
            if (role.isPaid()) {
                checkUsageBasedLimit(1);
            }
            List<UserInvitation> userInvitations =
                    userInvitationService.findByRoleAndEmail(role.getId(), user.getEmail());
            if (userInvitations.isEmpty()) {
                throw new CustomException("You are not invited to this organization for this role",
                        HttpStatus.NOT_ACCEPTABLE);
            }
            userInvitations.sort(Comparator.comparing(UserInvitation::getCreatedAt).reversed());
            user.setRole(role);
            if (role.getCompanySettings() == null) {
                Optional<User> optionalInviter = findById(userInvitations.get(0).getCreatedBy());
                if (optionalInviter.isEmpty())
                    throw new CustomException("Inviter not found", HttpStatus.NOT_ACCEPTABLE);
                user.setCompany(optionalInviter.get().getCompany());
            } else user.setCompany(role.getCompanySettings().getCompany());
            if (role.isPaid()) {
                int companyUsersCount =
                        (int) findByCompany(user.getCompany().getId()).stream().filter(user1 -> user1.isEnabled() && user1.isEnabledInSubscriptionAndPaid()).count();
                if (companyUsersCount + 1 > user.getCompany().getSubscription().getUsersCount())
                    throw new CustomException("You have reached the maximum number of users for your subscription",
                            HttpStatus.NOT_ACCEPTABLE);
            }
            return enableAndReturnToken(user, true, userReq);
        }
        if (Helper.isLocalhost(PUBLIC_API_URL)) {
            return enableAndReturnToken(user, false, userReq);
        } else {
            if (userReq.getRole() == null) { //send mail
                if (enableInvitationViaEmail) {
                    throwIfEmailNotificationsNotEnabled();
                    String token = UUID.randomUUID().toString();
                    String link = PUBLIC_API_URL + "/auth/activate-account?token=" + token;
                    Map<String, Object> variables = new HashMap<String, Object>() {{
                        put("verifyTokenLink", link);
                    }};
                    user = userRepository.save(user);
                    VerificationToken newUserToken = new VerificationToken(token, user, null);
                    verificationTokenRepository.save(newUserToken);
                    if (!Boolean.TRUE.equals(userReq.getSkipMailSending())) {
                        User signedUp = user;
                        externalSendService.mail(auditTarget(signedUp), () -> mailServiceFactory.getMailService()
                                .sendMessageUsingThymeleafTemplate(new String[]{signedUp.getEmail()},
                                        messageSource.getMessage("confirmation_email", null,
                                                Helper.getLocale(signedUp)), variables,
                                        "signup.html", Helper.getLocale(signedUp), null));
                    }
                } else {
                    return enableAndReturnToken(user, true, userReq);
                }
            }
            if (Boolean.TRUE.equals(userReq.getDemo()))
                return enableAndReturnToken(user, false, userReq);
            userRepository.save(user);
            cacheService.putUserInCache(user);
            onCompanyAndUserCreation(user);
            sendRegistrationMailToSuperAdmins(user, userReq);
            return new SignupSuccessResponse<>(true, "Successful registration. Check your mailbox to activate your " +
                    "account", null);
        }

    }

    public void delete(String username) {
        delete(username, null);
    }

    /**
     * P-11 (PPB-02, PPB-12): {@code DELETE /auth/{username}} of the super administrator. Deletes the user unless
     * other records cite them (409 USER_IN_USE with the blocking types) or they own their company (409
     * OWNER_ACCOUNT); records the deletion. An unknown username changes nothing.
     */
    public void delete(String username, User actor) {
        Optional<User> optionalUser = userRepository.findByUsername(username);
        if (optionalUser.isEmpty()) return;
        User user = optionalUser.get();
        rejectOwnerAccount(user);
        Long id = user.getId();
        deletionService.delete(new AuditTarget(EntityType.USER, id, null,
                        user.getFirstName() + " " + user.getLastName(), user.getCompany().getId()), actor,
                AuditSource.API,
                DeletionRule.inUse(USER_IN_USE, USER_IN_USE_MESSAGE)
                        .blockedBy(EntityType.ADDITIONAL_COST,
                                () -> deletionService.isCited("additional_cost", "assigned_to_id", id))
                        .blockedBy(EntityType.ASSET, () -> deletionService.isCited("asset", "primary_user_id", id))
                        .blockedBy(EntityType.COMMENT, () -> deletionService.isCited("comment", "user_id", id))
                        .blockedBy(EntityType.LABOR, () -> deletionService.isCited("labor", "assigned_to_id", id))
                        .blockedBy(EntityType.PREVENTIVE_MAINTENANCE,
                                () -> deletionService.isCited("preventive_maintenance", "primary_user_id", id))
                        .blockedBy(EntityType.REQUEST, () -> deletionService.isCited("request", "primary_user_id", id))
                        .blockedBy(EntityType.TASK_BASE, () -> deletionService.isCited("task_base", "user_id", id))
                        .blockedBy(EntityType.WORK_ORDER,
                                () -> deletionService.isCited("work_order", "completed_by_id", id)
                                        || deletionService.isCited("work_order", "primary_user_id", id)
                                        || workOrderRepository.isAssignedToClosedOrArchived(id))
                        .blockedBy(EntityType.WORK_ORDER_HISTORY,
                                () -> deletionService.isCited("work_order_aud", "completed_by_id", id)
                                        || deletionService.isCited("work_order_aud", "primary_user_id", id)
                                        || deletionService.isCited("work_order_history", "user_id", id))
                        .blockedBy(EntityType.WORK_ORDER_METER_TRIGGER,
                                () -> deletionService.isCited("work_order_meter_trigger", "primary_user_id", id))
                        .blockedBy(EntityType.WORKFLOW_ACTION,
                                () -> deletionService.isCited("workflow_action", "user_id", id))
                        .blockedBy(EntityType.WORKFLOW_CONDITION,
                                () -> deletionService.isCited("workflow_condition", "user_id", id)),
                () -> userRepository.deleteByUsername(username));
    }

    /**
     * P-11 (PPB-08): a disabled user is not accepted as a new reference. {@code requested} are the users a request
     * sets; those already in {@code previous} are kept even if disabled. Null elements are ignored.
     */
    public void rejectRetired(Collection<User> previous, Collection<User> requested) {
        if (requested == null) return;
        Set<Long> previousIds = new HashSet<>();
        if (previous != null) previous.stream().filter(Objects::nonNull).map(User::getId).forEach(previousIds::add);
        Set<Long> newIds = new HashSet<>();
        requested.stream().filter(Objects::nonNull).map(User::getId).filter(Objects::nonNull)
                .filter(userId -> !previousIds.contains(userId)).forEach(newIds::add);
        if (!newIds.isEmpty() && userRepository.existsByIdInAndEnabledFalse(newIds))
            throw new ConflictException(USER_RETIRED, USER_RETIRED_MESSAGE);
    }

    /**
     * P-18 (E-2): the same rule for users already loaded in the company (a row of an import), without a query. It is
     * static so that its 409 does not cross the transactional proxy of this service and mark the import for
     * rollback: the caller turns it into a rejected row.
     */
    public static void rejectRetiredLoaded(Collection<User> previous, Collection<User> requested) {
        Set<Long> previousIds = new HashSet<>();
        if (previous != null) previous.stream().filter(Objects::nonNull).map(User::getId).forEach(previousIds::add);
        if (requested != null && requested.stream().filter(Objects::nonNull)
                .anyMatch(user -> !user.isEnabled() && !previousIds.contains(user.getId())))
            throw new ConflictException(USER_RETIRED, USER_RETIRED_MESSAGE);
    }

    /**
     * P-16 (D-P16-10, INV-10): the users a request sets belong to the organization {@code companyId}; otherwise 403,
     * before any 409 on their state. Null elements are ignored.
     */
    public void rejectForeign(Collection<User> requested, Long companyId) {
        if (requested == null) return;
        Set<Long> ids = new HashSet<>();
        requested.stream().filter(Objects::nonNull).map(User::getId).filter(Objects::nonNull).forEach(ids::add);
        if (!ids.isEmpty() && userRepository.countByIdInAndCompany_Id(ids, companyId) < ids.size())
            throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email);
    }

    public Optional<User> findByEmailAndCompany(String email, Long companyId) {
        return userRepository.findByEmailIgnoreCaseAndCompany_Id(email, companyId);
    }

    public Optional<User> findByIdAndCompany(Long id, Long companyId) {
        return userRepository.findByIdAndCompany_Id(id, companyId);
    }

    public User whoami(HttpServletRequest req) {
        return whoami(req, true);
    }

    /**
     * The user of the request. With {@code cached}, the user that authenticated this request, loaded from the
     * database by the authentication filter: P-22 (C-11), it is never an instance shared with other requests.
     * Without {@code cached}, the user of the token loaded again in the current persistence context.
     */
    public User whoami(HttpServletRequest req, boolean cached) {
        String token = jwtTokenProvider.resolveToken(req);
        User authenticated = authenticatedUser();
        if (token == null || token.isEmpty()) {
            // API key authentication - get user from SecurityContext
            if (authenticated != null) {
                return authenticated;
            }
            throw new CustomException("Authentication required", HttpStatus.UNAUTHORIZED);
        }
        String username = jwtTokenProvider.getUsername(token);
        // P-22 (D-3): with a token, the user of the token, even when an API key of another user authenticated
        if (cached && authenticated != null && username.equalsIgnoreCase(authenticated.getEmail())) {
            return authenticated;
        }
        return findByEmail(username).get();
    }

    private static User authenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetail) {
            return ((CustomUserDetail) auth.getPrincipal()).getUser();
        }
        return null;
    }

    public String refresh(User user) {
        return jwtTokenProvider.createToken(user.getEmail(),
                Arrays.asList(user.getRole().getRoleType()));
    }

    public List<User> getAll() {
        return userRepository.findAll();
    }

    public long count() {
        return userRepository.count();
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public void enableUser(String email) {
        User user = userRepository.findByEmailIgnoreCase(email).get();
        enableUser(user);
    }

    /**
     * P-11 (PPB-23): {@code PATCH /users/{id}/enable}. Restoring a disabled user is recorded; enabling an enabled
     * user changes nothing and is not. Activation at signup and invitation use {@link #enableUser(User)} and are
     * not recorded.
     */
    public User restoreUser(User userToEnable, User requester) {
        boolean wasEnabled = userToEnable.isEnabled();
        User enabled = enableUser(userToEnable);
        if (!wasEnabled) record(enabled, requester, AuditAction.RESTORE);
        return enabled;
    }

    public User enableUser(User user) {
        if (user.getRole().isPaid()) {
            checkUsageBasedLimit(1);
            int companyUsersCount =
                    (int) findByCompany(user.getCompany().getId()).stream().filter(user1 -> user1.isEnabled() && user1.isEnabledInSubscriptionAndPaid()).count();
            if (companyUsersCount + 1 > user.getCompany().getSubscription().getUsersCount())
                throw new CustomException("You can't add more users to this company", HttpStatus.NOT_ACCEPTABLE);
        }
        user.setEnabled(true);
        user.setEnabledInSubscription(true);
        userRepository.save(user);
        cacheService.putUserInCache(user);
        return user;
    }

    public SuccessResponse resetPasswordRequest(String email) {
        throwIfEmailNotificationsNotEnabled();
        email = email.toLowerCase();
        User user = findByEmail(email).get();
        Helper helper = new Helper();
        String password = helper.generateString().replace("-", "").substring(0, 8).toUpperCase();

        String token = UUID.randomUUID().toString();
        Map<String, Object> variables = new HashMap<String, Object>() {{
            put("resetConfirmLink", PUBLIC_API_URL + "/auth/reset-pwd-confirm?token=" + token);
            put("password", password);
        }};
        VerificationToken newUserToken = new VerificationToken(token, user, password);
        verificationTokenRepository.save(newUserToken);
        mailServiceFactory.getMailService().sendMessageUsingThymeleafTemplate(new String[]{email},
                messageSource.getMessage("password_reset"
                        , new String[]{brandingService.getBrandConfig().getName()}, Helper.getLocale(user)), variables,
                "reset-password.html", Helper.getLocale(user), null);
        return new SuccessResponse(true, "Password changed successfully");
    }

    public Collection<User> findByCompany(Long id) {
        return userRepository.findByCompany_Id(id);
    }

    public Collection<User> findWorkersByCompany(Long id) {
        return userRepository.findWorkersByCompany(id, Arrays.asList(RoleCode.REQUESTER, RoleCode.VIEW_ONLY));
    }

    public Collection<User> findByLocation(Long id) {
        return userRepository.findByLocation_Id(id);
    }

    private void throwIfEmailNotificationsNotEnabled() {
        if (!enableMails)
            throw new CustomException("Please enable mails and configure SMTP in the environment variables",
                    HttpStatus.NOT_ACCEPTABLE);
    }

    public void invite(String email, Role role, User inviter, Boolean disableSendingMails) {
        if (!userRepository.existsByEmailIgnoreCase(email) && Helper.isValidEmailAddress(email)) {
            if (role.isPaid()) checkUsageBasedLimit(1);
            userInvitationService.create(new UserInvitation(email, role));
            if (!enableInvitationViaEmail || !enableMails) return;
            Map<String, Object> variables = new HashMap<String, Object>() {{
                put("joinLink", frontendUrl + "/account/register?" + "email=" + email + "&role=" + role.getId());
                put("inviter", inviter.getFirstName() + " " + inviter.getLastName());
                put("company", inviter.getCompany().getName());
            }};
            if (!Boolean.TRUE.equals(disableSendingMails)) {
                // P-23: an invitation has no record of its own yet; it is recorded against the organization
                Company company = inviter.getCompany();
                AuditTarget origin = new AuditTarget(EntityType.COMPANY, company.getId(), null, company.getName(),
                        company.getId());
                externalSendService.mail(origin, () -> mailServiceFactory.getMailService()
                        .sendMessageUsingThymeleafTemplate(new String[]{email},
                                messageSource.getMessage(
                                        "invitation_to_use", new String[]{brandingService.getBrandConfig().getName()},
                                        Helper.getLocale(inviter)), variables, "invite.html",
                                Helper.getLocale(inviter), null));
            }
        } else throw new CustomException("Email already in use", HttpStatus.NOT_ACCEPTABLE);
    }

    @org.springframework.transaction.annotation.Transactional
    public User update(Long id, UserPatchDTO userReq) {
        if (userRepository.existsById(id)) {
            User savedUser = userRepository.findById(id).get();
            if (userReq.getNewPassword() != null) {
                if (userReq.getNewPassword().length() < 8)
                    throw new CustomException("Password must be at least 8 characters", HttpStatus.NOT_ACCEPTABLE);
                if (enableInvitationViaEmail)
                    throw new CustomException("Please tell the user to reset his password", HttpStatus.NOT_FOUND);

                savedUser.setPassword(passwordEncoder.encode(userReq.getNewPassword()));
            }
            User updatedUser = userRepository.saveAndFlush(userMapper.updateUser(savedUser, userReq));
            em.refresh(updatedUser);
            cacheService.putUserInCache(updatedUser);
            return updatedUser;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    public Collection<User> saveAll(Collection<User> users) {
        return userRepository.saveAll(users);
    }

    public Page<User> findBySearchCriteria(SearchCriteria searchCriteria) {
        SpecificationBuilder<User> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return userRepository.findAll(builder.build(), page);
    }

    /**
     * P-23 (H-1): {@code @Async} did not apply (called on itself), so this mail went out in the thread of the
     * signup; it goes through {@link ExternalSendService} now.
     */
    void sendRegistrationMailToSuperAdmins(User user, UserSignupRequest userSignupRequest) {
        if (user.getRole() != null && user.getRole().getRoleType() == RoleType.ROLE_SUPER_ADMIN) return;
        if (user.getCompany() != null && user.getCompany().isDemo()) return;
        if (recipients == null || recipients.length == 0) {
            return;
//            throw new CustomException("MAIL_RECIPIENTS env variable not set", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        String subject = buildRegistrationEmailSubject(userSignupRequest, brandingService);
        String body = buildRegistrationEmailBody(user, userSignupRequest);
        MailService mailService = mailServiceFactory.getMailService();
        externalSendService.submit(ExternalSendService.Channel.MAIL, auditTarget(user), () -> {
            try {
                mailService.sendHtmlMessage(recipients, subject, body, null);
            } catch (MessagingException | IOException e) {
                throw new IllegalStateException("The registration mail could not be sent");
            }
        });
    }

    /**
     * P-23: the user in the audit log, by name, never by email.
     */
    private static AuditTarget auditTarget(User user) {
        return new AuditTarget(EntityType.USER, user.getId(), null, user.getFirstName() + " " + user.getLastName(),
                user.getCompany() == null ? null : user.getCompany().getId());
    }

    private String buildRegistrationEmailSubject(UserSignupRequest request, BrandingService brandingService) {
        String brandName = brandingService.getBrandConfig().getShortName();

        if (request.getSubscriptionPlanId() == null) {
            return String.format("New %s registration", brandName);
        }

        return String.format("%s plan %s used", brandName, request.getSubscriptionPlanId());
    }

    private String buildRegistrationEmailBody(User user, UserSignupRequest request) {
        StringBuilder body = new StringBuilder();

        // User basic info
        body.append(String.format("%s %s just created an account%n",
                user.getFirstName(),
                user.getLastName()));

        // Company info
        body.append(String.format("Company: %s (%s employees)%n",
                user.getCompany().getName(),
                request.getEmployeesCount()));

        // Contact info
        body.append(String.format("Email: %s%n", user.getEmail()));
        body.append(String.format("Phone: %s%n", user.getPhone()));

        // Registration type
        if (!user.isOwnsCompany()) {
            body.append("Registration type: After invitation\n");
        }

        // UTM parameters
        appendUtmParameters(body, request);

        return body.toString();
    }

    public SuccessResponse inviteUsers(UserInvitationDTO invitation, User user) {
        if (user.getRole().getCreatePermissions().contains(PermissionEntity.PEOPLE_AND_TEAMS)) {
            int companyUsersCount =
                    (int) findByCompany(user.getCompany().getId()).stream().filter(user1 -> user1.isEnabled() && user1.isEnabledInSubscriptionAndPaid()).count();
            Optional<Role> optionalRole = roleService.findById(invitation.getRole().getId());
            if (optionalRole.isPresent() && optionalRole.get().belongsToCompany(user.getCompany())) {
                roleService.assertCanGrant(user.getRole(), optionalRole.get());
                if (companyUsersCount + invitation.getEmails().size() <= user.getCompany().getSubscription().getUsersCount() || !optionalRole.get().isPaid()) {
                    invitation.getEmails().forEach(email ->
                            invite(email, optionalRole.get(), user, invitation.getDisableSendingEmail())
                    );

                    // Fire Intercom event for first user invitation
                    if (!user.getCompany().isInvitedUsers() && !invitation.getEmails().isEmpty()) {
                        user.getCompany().setInvitedUsers(true);
                        companyService.update(user.getCompany());
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("invited_count", invitation.getEmails().size());
                        intercomService.createCompanyActivationEvent(
                                "first-users-invited",
                                user.getCompany().getId(),
                                user.getEmail(),
                                metadata
                        );
                    }

                    return new SuccessResponse(true, "Users have been invited");
                } else
                    throw new CustomException("Your current subscription doesn't allow you to invite that many users"
                            , HttpStatus.NOT_ACCEPTABLE);

            } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
        } else throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
    }

    public User patchUserRole(Long userId, Long roleId, User requester) {
        // INC-02 (C11): nobody changes their own role.
        if (requester.getId().equals(userId))
            throw new CustomException("You cannot change your own role", HttpStatus.FORBIDDEN);
        Optional<User> optionalUserToPatch = findByIdAndCompany(userId, requester.getCompany().getId());
        Optional<Role> optionalRole = roleService.findById(roleId);

        if (optionalUserToPatch.isPresent() && optionalRole.isPresent() && optionalRole.get().belongsToCompany(requester.getCompany())) {
            User userToPatch = optionalUserToPatch.get();
            if (requester.getRole().getEditOtherPermissions().contains(PermissionEntity.PEOPLE_AND_TEAMS)) {
                // INC-02 (D-6, D-21): both the current role of the user and the resulting one
                assertCanManage(requester, userToPatch);
                roleService.assertCanGrant(requester.getRole(), optionalRole.get());
                int usersCount =
                        (int) findByCompany(requester.getCompany().getId()).stream().filter(User::isEnabledInSubscriptionAndPaid).count();
                if (usersCount <= requester.getCompany().getSubscription().getUsersCount()) {
                    userToPatch.setRole(optionalRole.get());
                    return save(userToPatch);
                } else
                    throw new CustomException("Company subscription users count doesn't allow this operation",
                            HttpStatus.NOT_ACCEPTABLE);
            } else {
                throw new CustomException("You don't have permission", HttpStatus.NOT_ACCEPTABLE);
            }
        } else {
            throw new CustomException("User or role not found", HttpStatus.NOT_FOUND);
        }
    }

    /**
     * INC-02 (D-21, D-22): the single rule to manage another user, that is to change their role or password, disable
     * or delete them. Nobody manages the company owner but the owner, nor a user whose current role is superior to
     * their own (D-6). Changing one's own role is forbidden apart (D-7).
     */
    public void assertCanManage(User manager, User target) {
        if (manager.getId().equals(target.getId())) return;
        if (target.isOwnsCompany())
            throw new CustomException("Only the company owner can manage their own user", HttpStatus.FORBIDDEN);
        if (!roleService.isWithin(manager.getRole(), target.getRole()))
            throw new CustomException("You cannot manage a user whose role is superior to yours", HttpStatus.FORBIDDEN);
    }

    /**
     * P-11 (PPB-10, PPB-12): the owner never deletes nor disables their own account.
     */
    private static void rejectOwnerAccount(User target) {
        if (target.isOwnsCompany()) throw new ConflictException(OWNER_ACCOUNT, OWNER_ACCOUNT_MESSAGE);
    }

    /**
     * P-11 (PPB-11): "delete my account" of a user who does not own the company disables that user, who keeps
     * every record; the owner is rejected with 409 OWNER_ACCOUNT and nothing changes.
     */
    public void deleteOwnAccount(User currentUser) {
        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new CustomException("User not found", HttpStatus.NOT_FOUND));
        rejectOwnerAccount(user);
        retire(user, user);
    }

    /**
     * Disables {@code user}, records the retirement if the user was enabled (PPB-23) and, once committed, removes
     * the user from the cache, so that the previous tokens stop authenticating at once (CA-PPB-032).
     */
    private User retire(User user, User actor) {
        boolean wasEnabled = user.isEnabled();
        user.setEnabled(false);
        user.setEnabledInSubscription(false);
        User saved = save(user);
        if (wasEnabled) record(saved, actor, AuditAction.RETIRE);
        evictAfterCommit(saved.getEmail());
        return saved;
    }

    private void record(User user, User actor, AuditAction action) {
        auditLogService.record(new AuditTarget(EntityType.USER, user.getId(), null,
                        user.getFirstName() + " " + user.getLastName(), user.getCompany().getId()), actor, action,
                AuditSource.API, Map.of(), 0);
    }

    private void evictAfterCommit(String email) {
        AfterCommit.run(() -> cacheService.evictUserFromCache(email));
    }

    public User disableUser(Long id, User requester) {
        Optional<User> optionalUserToDisable = findByIdAndCompany(id, requester.getCompany().getId());

        if (optionalUserToDisable.isPresent()) {
            User userToDisable = optionalUserToDisable.get();
            if (requester.getRole().getEditOtherPermissions().contains(PermissionEntity.PEOPLE_AND_TEAMS)) {
                assertCanManage(requester, userToDisable);
                rejectOwnerAccount(userToDisable);
                return retire(userToDisable, requester);
            } else {
                throw new CustomException("You don't have permission", HttpStatus.NOT_ACCEPTABLE);
            }
        } else {
            throw new CustomException("User or role not found", HttpStatus.NOT_FOUND);
        }
    }

    public User softDeleteUser(Long id, User requester) {
        Optional<User> optionalUserToSoftDelete = findByIdAndCompany(id, requester.getCompany().getId());

        if (optionalUserToSoftDelete.isPresent()) {
            User userToSoftDelete = optionalUserToSoftDelete.get();
            if (requester.getId().equals(id) || requester.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS)) {
                assertCanManage(requester, userToSoftDelete);
                rejectOwnerAccount(userToSoftDelete);
                String previousEmail = userToSoftDelete.getEmail();
                userToSoftDelete.setEnabled(false);
                userToSoftDelete.setEnabledInSubscription(false);
                userToSoftDelete.setEmail(userToSoftDelete.getEmail().concat("_".concat(id.toString())));
                User saved = save(userToSoftDelete);
                // P-11: the email changes, so the retirement is recorded even if the user was already disabled
                record(saved, requester, AuditAction.RETIRE);
                evictAfterCommit(previousEmail);
                return saved;
            } else {
                throw new CustomException("You don't have permission", HttpStatus.NOT_ACCEPTABLE);
            }
        } else {
            throw new CustomException("User not found", HttpStatus.NOT_FOUND);
        }
    }

    private void appendUtmParameters(StringBuilder body, UserSignupRequest request) {
        if (!cloudVersion || request.getUtmParams() == null || !request.getUtmParams().hasAnyParam()) return;
        body.append("\n--- Marketing Attribution ---\n");
        if (request.getUtmParams().getReferrer() != null) {
            body.append(String.format("Referrer: %s%n", request.getUtmParams().getReferrer()));
        }
        if (request.getUtmParams().getUtm_source() != null) {
            body.append(String.format("UTM Source: %s%n", request.getUtmParams().getUtm_source()));
        }
        if (request.getUtmParams().getUtm_medium() != null) {
            body.append(String.format("UTM Medium: %s%n", request.getUtmParams().getUtm_medium()));
        }
        if (request.getUtmParams().getUtm_campaign() != null) {
            body.append(String.format("UTM Campaign: %s%n", request.getUtmParams().getUtm_campaign()));
        }
        if (request.getUtmParams().getUtm_term() != null) {
            body.append(String.format("UTM Term: %s%n", request.getUtmParams().getUtm_term()));
        }
        if (request.getUtmParams().getUtm_content() != null) {
            body.append(String.format("UTM Content: %s%n", request.getUtmParams().getUtm_content()));
        }
        if (request.getUtmParams().getGclid() != null) {
            body.append(String.format("Google Click ID: %s%n", request.getUtmParams().getGclid()));
        }
        if (request.getUtmParams().getFbclid() != null) {
            body.append(String.format("Facebook Click ID: %s%n", request.getUtmParams().getFbclid()));
        }
    }
}

