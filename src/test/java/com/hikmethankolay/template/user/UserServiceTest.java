package com.hikmethankolay.template.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.user.dto.AdminUpdateUserRequest;
import com.hikmethankolay.template.user.dto.ChangePasswordRequest;
import com.hikmethankolay.template.user.dto.DeleteAccountRequest;
import com.hikmethankolay.template.user.dto.UpdateProfileRequest;
import com.hikmethankolay.template.user.dto.UserResponse;
import com.hikmethankolay.template.user.validation.BreachedPasswordValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String PASSPHRASE = "glacier-umbrella-tractor-71";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher events;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                passwordEncoder,
                new BreachedPasswordValidator(TestProperties.create()),
                events,
                new AuditLog(
                        new SimpleMeterRegistry(),
                        mock(JdbcTemplate.class),
                        mock(PlatformTransactionManager.class),
                        JsonMapper.builder().build()));
    }

    @Test
    void registerNormalizesEmailAndHashesPassword() {
        when(passwordEncoder.encode("Secret#123-passphrase")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        UserResponse response =
                userService.register("alice", "  Alice@Example.COM ", "Secret#123-passphrase", Role.USER, false);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.role()).isEqualTo(Role.USER);
        assertThat(response.emailVerified()).isFalse();
        verify(events).publishEvent(new EmailVerificationRequired(1L));
    }

    @Test
    void registerVerifiedUserSendsNoVerification() {
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 2L));

        UserResponse response =
                userService.register("root", "root@example.com", "Secret#123-passphrase", Role.ADMIN, true);

        assertThat(response.emailVerified()).isTrue();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void registerRejectsTakenEmail() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() ->
                        userService.register("alice", "alice@example.com", "Secret#123-passphrase", Role.USER, false))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).save(any());
    }

    @Test
    void confirmedSignUpCreatesAVerifiedUserWithTheStoredHash() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 4L));

        UserResponse response = userService.registerConfirmed("alice", "alice@example.com", "{argon2}stored");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{argon2}stored");
        assertThat(saved.getValue().isEmailVerified()).isTrue();
        assertThat(response.role()).isEqualTo(Role.USER);
        // Already verified: no second verification email.
        verify(events, never()).publishEvent(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void confirmedSignUpFailsIfTheAddressWasTakenSince() {
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertProblem(
                () -> userService.registerConfirmed("alice", "alice@example.com", "{argon2}stored"),
                HttpStatus.CONFLICT,
                "urn:problem:email-taken");
        verify(userRepository, never()).save(any());
    }

    @Test
    void newPasswordsAreCheckedAndHashed() {
        when(passwordEncoder.encode(PASSPHRASE)).thenReturn("hashed");

        assertThat(userService.encodeNewPassword(false, PASSPHRASE)).isEqualTo("hashed");
        assertProblem(
                () -> userService.encodeNewPassword(false, "fourteen-chars"),
                HttpStatus.BAD_REQUEST,
                "urn:problem:password-too-short");
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(1L, new ChangePasswordRequest("wrong", "New#12345")))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(user.getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void newPasswordNeeds15CharactersWithoutTwoFactor() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current", "hashed")).thenReturn(true);

        assertProblem(
                () -> userService.changePassword(1L, new ChangePasswordRequest("current", "fourteen-chars")),
                HttpStatus.BAD_REQUEST,
                "urn:problem:password-too-short");
        assertThat(user.getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void twoFactorUsersMaySet8Characters() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        user.startTotpEnrollment("encrypted");
        user.enableTotp(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current", "hashed")).thenReturn(true);
        when(passwordEncoder.encode("tq8-vbmz")).thenReturn("short-hash");

        userService.changePassword(1L, new ChangePasswordRequest("current", "tq8-vbmz"));

        assertThat(user.getPasswordHash()).isEqualTo("short-hash");
    }

    @Test
    void breachedPasswordIsRejectedEvenWithTwoFactor() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        user.startTotpEnrollment("encrypted");
        user.enableTotp(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertProblem(
                () -> userService.setPassword(1L, "Password123456"),
                HttpStatus.BAD_REQUEST,
                "urn:problem:password-breached");
        assertThat(user.getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void passwordLengthCountsCodePoints() {
        // 15 emoji are 30 UTF-16 chars but 15 characters.
        when(passwordEncoder.encode("😀".repeat(15))).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));

        userService.register("alice", "alice@example.com", "😀".repeat(15), Role.USER, true);

        assertProblem(
                () -> userService.register("bob", "bob@example.com", "😀".repeat(14), Role.USER, true),
                HttpStatus.BAD_REQUEST,
                "urn:problem:password-too-short");
    }

    @Test
    void profileUpdateWithTheSameEmailNeedsNoPassword() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        user.setEmailVerified(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        userService.updateProfile(1L, new UpdateProfileRequest("alice", " Alice@Example.COM ", null));

        // Same address after normalising: still verified, no new verification email.
        assertThat(user.isEmailVerified()).isTrue();
        verify(passwordEncoder, never()).matches(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void profileUpdateOfTheUsernameAloneNeedsNoPassword() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        UserResponse response = userService.updateProfile(1L, new UpdateProfileRequest("alice2", null, null));

        assertThat(response.username()).isEqualTo("alice2");
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void adminUpdateWithoutChangesKeepsTheSessions() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        userService.update(1L, 5L, new AdminUpdateUserRequest("alice", "ALICE@example.com", null, Role.USER, null));

        assertThat(user.getSessionVersion()).isZero();
        verify(events, never()).publishEvent(any());
    }

    @Test
    void adminEmailChangeAsksForVerificationButKeepsTheSessions() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        user.setEmailVerified(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        userService.update(1L, 5L, new AdminUpdateUserRequest(null, "new@example.com", null, null, null));

        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getSessionVersion()).isZero();
        verify(events).publishEvent(new EmailVerificationRequired(1L));
    }

    @Test
    void adminUpdateLeavesNullFieldsUnchanged() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.findById(9L)).thenReturn(Optional.of(owner()));
        when(passwordEncoder.matches("Owner#123", "owner-hash")).thenReturn(true);
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        UserResponse response =
                userService.update(1L, 9L, new AdminUpdateUserRequest(null, null, null, Role.ADMIN, "Owner#123"));

        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(user.getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void ownerRoleCannotBeAssigned() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertProblem(
                () -> userService.update(1L, 9L, new AdminUpdateUserRequest(null, null, null, Role.OWNER, "Owner#123")),
                HttpStatus.CONFLICT,
                "urn:problem:owner-protected");
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void ownerCannotBeDemotedEvenByThemselves() {
        User owner = owner();
        when(userRepository.findById(9L)).thenReturn(Optional.of(owner));

        assertProblem(
                () -> userService.update(9L, 9L, new AdminUpdateUserRequest(null, null, null, Role.USER, "Owner#123")),
                HttpStatus.CONFLICT,
                "urn:problem:owner-protected");
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
    }

    @Test
    void onlyTheOwnerCanChangeRoles() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.findById(5L)).thenReturn(Optional.of(admin(5L)));

        assertProblem(
                () -> userService.update(1L, 5L, new AdminUpdateUserRequest(null, null, null, Role.ADMIN, "Admin#123")),
                HttpStatus.FORBIDDEN,
                "urn:problem:owner-required");
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void administratorsCannotChangeOtherAdministrators() {
        User other = admin(6L);
        when(userRepository.findById(6L)).thenReturn(Optional.of(other));
        when(userRepository.findById(5L)).thenReturn(Optional.of(admin(5L)));

        assertProblem(
                () -> userService.update(6L, 5L, new AdminUpdateUserRequest(null, null, "Taken#over1", null, null)),
                HttpStatus.FORBIDDEN,
                "urn:problem:owner-required");
        assertThat(other.getPasswordHash()).isEqualTo("hashed");
    }

    @Test
    void roleChangeNeedsTheOwnersPassword() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.findById(9L)).thenReturn(Optional.of(owner()));
        when(passwordEncoder.matches("Wrong#123", "owner-hash")).thenReturn(false);

        assertProblem(
                () -> userService.update(1L, 9L, new AdminUpdateUserRequest(null, null, null, Role.ADMIN, "Wrong#123")),
                HttpStatus.BAD_REQUEST,
                "urn:problem:wrong-password");
        assertProblem(
                () -> userService.update(1L, 9L, new AdminUpdateUserRequest(null, null, null, Role.ADMIN, null)),
                HttpStatus.BAD_REQUEST,
                "urn:problem:wrong-password");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void ownerDemotesAnAdministratorAndEndsTheirSessions() {
        User other = admin(6L);
        when(userRepository.findById(6L)).thenReturn(Optional.of(other));
        when(userRepository.findById(9L)).thenReturn(Optional.of(owner()));
        when(passwordEncoder.matches("Owner#123", "owner-hash")).thenReturn(true);
        when(userRepository.saveAndFlush(other)).thenReturn(other);

        UserResponse response =
                userService.update(6L, 9L, new AdminUpdateUserRequest(null, null, null, Role.USER, "Owner#123"));

        assertThat(response.role()).isEqualTo(Role.USER);
        verify(events).publishEvent(new SessionsInvalidated(6L));
    }

    @Test
    void administratorsCanManageRegularUsersWithoutTheOwner() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("Reset#1234-passphrase")).thenReturn("new-hash");
        when(userRepository.saveAndFlush(user)).thenReturn(user);

        userService.update(1L, 5L, new AdminUpdateUserRequest(null, null, "Reset#1234-passphrase", null, null));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void deleteRejectsOwnAccount() {
        assertThatThrownBy(() -> userService.delete(5L, 5L, null))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).delete(any());
    }

    @Test
    void getByIdThrowsNotFoundForUnknownUser() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(9L))
                .isInstanceOfSatisfying(
                        ApiException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void ownerCannotDeleteTheirAccount() {
        when(userRepository.findById(9L)).thenReturn(Optional.of(owner()));
        when(passwordEncoder.matches("Owner#123", "owner-hash")).thenReturn(true);

        assertProblem(
                () -> userService.deleteOwnAccount(9L, new DeleteAccountRequest("Owner#123")),
                HttpStatus.CONFLICT,
                "urn:problem:owner-protected");
        verify(userRepository, never()).delete(any());
    }

    @Test
    void nobodyCanDeleteTheOwner() {
        when(userRepository.findById(9L)).thenReturn(Optional.of(owner()));

        assertProblem(
                () -> userService.delete(9L, 5L, new DeleteAccountRequest("Admin#123")),
                HttpStatus.CONFLICT,
                "urn:problem:owner-protected");
        verify(userRepository, never()).delete(any());
    }

    @Test
    void deletingAnAdministratorNeedsTheOwnerAndTheirPassword() {
        User other = admin(6L);
        when(userRepository.findById(6L)).thenReturn(Optional.of(other));
        when(userRepository.findById(5L)).thenReturn(Optional.of(admin(5L)));
        when(userRepository.findById(9L)).thenReturn(Optional.of(owner()));
        when(passwordEncoder.matches("Wrong#123", "owner-hash")).thenReturn(false);
        when(passwordEncoder.matches("Owner#123", "owner-hash")).thenReturn(true);

        assertProblem(
                () -> userService.delete(6L, 5L, new DeleteAccountRequest("Admin#123")),
                HttpStatus.FORBIDDEN,
                "urn:problem:owner-required");
        assertProblem(() -> userService.delete(6L, 9L, null), HttpStatus.BAD_REQUEST, "urn:problem:wrong-password");
        assertProblem(
                () -> userService.delete(6L, 9L, new DeleteAccountRequest("Wrong#123")),
                HttpStatus.BAD_REQUEST,
                "urn:problem:wrong-password");
        verify(userRepository, never()).delete(any());

        userService.delete(6L, 9L, new DeleteAccountRequest("Owner#123"));

        verify(userRepository).delete(other);
        verify(events).publishEvent(new SessionsInvalidated(6L));
    }

    @Test
    void administratorsDeleteRegularUsersWithoutConfirmation() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.delete(1L, 5L, null);

        verify(userRepository).delete(user);
    }

    @Test
    void deletingOwnAccountEndsItsSessions() {
        User user = withId(new User("alice", "alice@example.com", "hashed", Role.USER), 5L);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Secret#123-passphrase", "hashed")).thenReturn(true);

        userService.deleteOwnAccount(5L, new DeleteAccountRequest("Secret#123-passphrase"));

        verify(userRepository).delete(user);
        verify(events).publishEvent(new SessionsInvalidated(5L));
    }

    private static User owner() {
        return withId(new User("owner", "owner@example.com", "owner-hash", Role.OWNER), 9L);
    }

    private static User admin(long id) {
        return withId(new User("admin" + id, "admin" + id + "@example.com", "hashed", Role.ADMIN), id);
    }

    private static void assertProblem(ThrowingCallable call, HttpStatus status, String type) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatusCode()).isEqualTo(status);
            assertThat(ex.getBody().getType()).hasToString(type);
        });
    }

    private static User withId(User user, long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
