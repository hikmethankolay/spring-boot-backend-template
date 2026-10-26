package com.hikmethankolay.template.user.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Password length rule: 8-128 characters and no composition rules (NIST SP 800-63B-4). This is the
 * floor for users with two-factor authentication; {@code UserService} asks 15 characters of everyone
 * else and checks every new password against {@code BreachedPasswordValidator}.
 *
 * <p>{@code null} is valid; combine with {@code @NotBlank} when the field is required.</p>
 */
@Documented
@Constraint(validatedBy = {})
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Size(min = 8, max = 128)
@ReportAsSingleViolation
public @interface ValidPassword {

    /**
     * Error message reported to the client.
     *
     * @return the message
     */
    String message() default "must be 8-128 characters";

    /**
     * Validation groups.
     *
     * @return the groups
     */
    Class<?>[] groups() default {};

    /**
     * Validation payload.
     *
     * @return the payload types
     */
    Class<? extends Payload>[] payload() default {};
}
