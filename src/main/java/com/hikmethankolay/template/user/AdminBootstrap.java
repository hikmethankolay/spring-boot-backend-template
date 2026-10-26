package com.hikmethankolay.template.user;

import com.hikmethankolay.template.common.ApiException;
import com.hikmethankolay.template.config.AppProperties;
import com.hikmethankolay.template.user.validation.ValidEmail;
import com.hikmethankolay.template.user.validation.ValidPassword;
import com.hikmethankolay.template.user.validation.ValidUsername;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Creates the owner account configured in {@code app.admin.*} on startup, if username, email and
 * password are all set and no owner exists yet. This solves the "who creates the first admin"
 * problem without manual SQL. The owner has every administrator right and is the only one who can
 * change roles and manage administrators (see {@link Role#OWNER}).
 *
 * <p>The credentials must follow the same rules as a registration, or the application does not start. If the
 * configured username or email already belongs to a regular user, registration fails with a conflict and the
 * application does not start either.</p>
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserService userService;

    private final AppProperties.Admin admin;

    private final Validator validator;

    /**
     * Creates the runner.
     *
     * @param userService creates the admin account
     * @param properties  application properties holding the admin credentials
     * @param validator   checks the credentials against the registration rules
     */
    AdminBootstrap(UserService userService, AppProperties properties, Validator validator) {
        this.userService = userService;
        this.admin = properties.admin();
        this.validator = validator;
    }

    /**
     * Creates the admin when it is configured and no admin exists.
     *
     * @param args application arguments (unused)
     * @throws IllegalStateException if the credentials break the registration rules
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!admin.isConfigured() || userService.ownerExists()) {
            return;
        }
        checkCredentials();
        // The configured admin address is trusted, so no verification email is sent.
        try {
            userService.register(admin.username(), admin.email(), admin.password(), Role.OWNER, true);
            log.info("Created the owner account '{}'", admin.username());
        } catch (ApiException | DataIntegrityViolationException ex) {
            // Several instances starting at once all try; only a conflict with another account is an error.
            if (!userService.ownerExists()) {
                throw ex;
            }
            log.info("Another instance created the owner account");
        }
    }

    private void checkCredentials() {
        String problems =
                validator.validate(new Credentials(admin.username(), admin.email(), admin.password())).stream()
                        // The messages never contain the rejected value, so the password is not logged.
                        .map(violation -> "ADMIN_"
                                + violation.getPropertyPath().toString().toUpperCase(Locale.ROOT) + " "
                                + violation.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The owner account was not created: " + problems);
        }
    }

    /**
     * The registration rules of {@code RegisterRequest}, applied to the configured credentials. The owner has
     * no two-factor authentication yet, so the password needs {@link UserService#MIN_PASSWORD_LENGTH}
     * characters; {@code UserService} also refuses a breached one.
     */
    private record Credentials(
            @NotBlank @ValidUsername String username,
            @NotBlank @ValidEmail String email,

            @NotBlank @ValidPassword @Size(min = UserService.MIN_PASSWORD_LENGTH)
            String password) {}
}
