package com.hikmethankolay.template.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.audit.AuditEvent;
import com.hikmethankolay.template.audit.AuditLog;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.mail.SecurityMailer;
import com.hikmethankolay.template.security.LoginAttemptService;
import com.hikmethankolay.template.user.Role;
import com.hikmethankolay.template.user.User;
import com.hikmethankolay.template.user.UserRepository;
import com.hikmethankolay.template.user.UserService;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Unit tests of unlinking a provider account in {@link SocialAccountService}. */
class SocialAccountServiceTest {

    private final SocialIdentityRepository identities = mock(SocialIdentityRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final SecurityMailer mailer = mock(SecurityMailer.class);
    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SocialAccountService service = new SocialAccountService(
            identities, users, mock(UserService.class), audit, mailer, loginAttempts, passwordEncoder);

    private final SocialIdentity identity = new SocialIdentity(1L, "github", "42");

    /** The right password unlinks the account, audits it and alerts the user. */
    @Test
    void unlinkingWithTheRightPasswordRemovesTheLink() {
        givenLinkAndPassword(true);

        service.unlink(1L, 5L, "secret");

        verify(identities).delete(identity);
        verify(audit).record(AuditEvent.SOCIAL_ACCOUNT_UNLINKED, 1L, "provider", "github");
        verify(mailer).socialAccountUnlinked("user@example.com", "github");
    }

    /** A wrong password changes nothing and goes through the login limiter. */
    @Test
    void unlinkingWithAWrongPasswordIsRefused() {
        givenLinkAndPassword(false);

        assertThatThrownBy(() -> service.unlink(1L, 5L, "wrong"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType()).hasToString("urn:problem:wrong-password"));
        verify(loginAttempts).checkReentered(anyLong(), any());
        verify(identities, never()).delete(any());
        verifyNoInteractions(audit, mailer);
    }

    /** Another user's link, or an unknown one, is missing; the password is not checked. */
    @Test
    void unlinkingAnUnknownLinkIsNotFound() {
        when(identities.findByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlink(1L, 5L, "secret"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        ex -> assertThat(ex.getBody().getType()).hasToString("urn:problem:social-account-not-found"));
        verifyNoInteractions(loginAttempts, audit, mailer);
    }

    private void givenLinkAndPassword(boolean matches) {
        when(identities.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(identity));
        when(users.findById(1L)).thenReturn(Optional.of(new User("user", "user@example.com", "hash", Role.USER)));
        when(passwordEncoder.matches(any(), any())).thenReturn(matches);
        when(loginAttempts.checkReentered(anyLong(), any()))
                .thenAnswer(call -> call.<BooleanSupplier>getArgument(1).getAsBoolean());
    }
}
