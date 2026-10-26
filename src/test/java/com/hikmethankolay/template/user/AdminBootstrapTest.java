package com.hikmethankolay.template.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hikmethankolay.template.TestProperties;
import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.common.ProblemType;
import com.hikmethankolay.template.config.AppProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private UserService users;

    private AdminBootstrap bootstrap() {
        return bootstrap("Owner#12345-passphrase");
    }

    private AdminBootstrap bootstrap(String password) {
        AppProperties p = TestProperties.create();
        return new AdminBootstrap(
                users,
                new AppProperties(
                        p.jwt(),
                        p.cookie(),
                        p.cors(),
                        p.login(),
                        p.rateLimit(),
                        p.account(),
                        p.mail(),
                        new AppProperties.Admin("owner", "owner@example.com", password),
                        p.mfa(),
                        p.socialLogin()),
                VALIDATOR);
    }

    @Test
    void anotherInstanceCreatingTheOwnerFirstIsNotAnError() {
        when(users.ownerExists()).thenReturn(false, true);
        when(users.register(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatCode(() -> bootstrap().run(null)).doesNotThrowAnyException();
    }

    @Test
    void aConflictWithAnotherAccountStillStopsStartup() {
        when(users.ownerExists()).thenReturn(false);
        when(users.register(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(ApiException.conflict(ProblemType.USERNAME_TAKEN, "Username is already taken."));

        assertThatThrownBy(() -> bootstrap().run(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void aPasswordThatBreaksThePasswordRuleStopsStartup() {
        when(users.ownerExists()).thenReturn(false);

        assertThatThrownBy(() -> bootstrap("weakpass").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD")
                .hasMessageNotContaining("weakpass");
        verify(users, never()).register(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void credentialsAreNotCheckedOnceTheOwnerExists() {
        when(users.ownerExists()).thenReturn(true);

        assertThatCode(() -> bootstrap("weakpass").run(null)).doesNotThrowAnyException();
    }
}
