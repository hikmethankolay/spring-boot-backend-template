package com.hikmethankolay.template.common;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as an RFC 9457 problem detail ({@code application/problem+json}).
 *
 * <p>Example response:</p>
 * <pre>
 * HTTP/1.1 400 Bad Request
 * Content-Type: application/problem+json
 *
 * {"type":"urn:problem:validation-failed","title":"Bad Request","status":400,"detail":"Request validation failed.",
 *  "instance":"/api/v1/auth/register","errors":[{"field":"email","message":"must be a well-formed email address"}]}
 * </pre>
 *
 * <p>Spring MVC exceptions and {@link ApiException} are handled by the base class. Errors raised
 * in the security filter chain are routed here as well (see {@code SecurityProblemHandler}).</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** RFC 9457 type of problems that need no type of their own. */
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    /** Creates the handler; Spring instantiates it. */
    public GlobalExceptionHandler() {
        // Nothing to initialise.
    }

    /**
     * Maps missing, invalid or expired credentials to {@code 401 Unauthorized}.
     *
     * @param ex the authentication failure
     * @return a 401 problem with a {@code WWW-Authenticate: Bearer} header
     */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ex instanceof BadCredentialsException
                ? ProblemType.INVALID_CREDENTIALS.problem(HttpStatus.UNAUTHORIZED, ex.getMessage())
                : ProblemType.UNAUTHENTICATED.problem(
                        HttpStatus.UNAUTHORIZED, "Authentication is required to access this resource.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    /**
     * Maps authenticated-but-not-allowed requests to {@code 403 Forbidden}.
     *
     * @param ex the authorization failure
     * @return a 403 problem
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemType.FORBIDDEN.problem(
                HttpStatus.FORBIDDEN, "You do not have permission to access this resource.");
    }

    /**
     * Maps database constraint violations (e.g. a unique key race) and writes based on a stale read
     * (optimistic locking) to {@code 409 Conflict}.
     *
     * @param ex the violation; its details are logged, never sent to the client
     * @return a 409 problem
     */
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ProblemDetail handleDataIntegrity(DataAccessException ex) {
        // The database message repeats the conflicting value (e.g. an email), so only the constraint is logged.
        log.warn(
                "Conflicting write: {}",
                ex.getCause() instanceof ConstraintViolationException violation
                        ? "constraint " + violation.getConstraintName()
                        : ex.getClass().getSimpleName());
        return ProblemType.CONFLICT.problem(HttpStatus.CONFLICT, "The request conflicts with existing data.");
    }

    /**
     * Maps an unknown {@code sort} property in a paginated request to {@code 400 Bad Request}.
     *
     * @param ex the unresolved property reference
     * @return a 400 problem naming the property
     */
    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail handleInvalidSortProperty(PropertyReferenceException ex) {
        return ProblemType.INVALID_SORT.problem(
                HttpStatus.BAD_REQUEST, "Unknown sort property: " + ex.getPropertyName());
    }

    /**
     * Catch-all for unexpected errors: logs the stack trace and returns a generic {@code 500}.
     *
     * @param ex the unexpected exception
     * @return a 500 problem that does not leak internal details
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemType.INTERNAL_ERROR.problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }

    /**
     * Gives Spring MVC's own errors (malformed JSON, a path or query value of the wrong type, an unsupported method or
     * media type, ...) the {@code about:blank} type the API documents; Spring leaves {@code type} out.
     *
     * @param body       the problem detail, or another body
     * @param headers    response headers
     * @param statusCode response status
     * @param request    the current request
     * @return the response
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getType() == null) {
            problem.setType(ABOUT_BLANK);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /**
     * Adds field-level details to Bean Validation failures of {@code @Valid} request bodies.
     *
     * @param ex      the validation failure
     * @param headers headers prepared by the base class
     * @param status  the response status (400)
     * @param request the current request
     * @return a 400 problem with an {@code errors} array of {@link FieldViolation}s
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationFailed(ex, ex.getBody(), violations(ex.getBindingResult()), headers, status, request);
    }

    /**
     * Gives failures of Spring's built-in method validation (constraints on a {@code @RequestParam} or
     * {@code @PathVariable}, which also validate the method's {@code @Valid} body) the same problem as
     * {@link #handleMethodArgumentNotValid}.
     *
     * @param ex      the validation failure
     * @param headers headers prepared by the base class
     * @param status  the response status (400, or 500 for an invalid return value)
     * @param request the current request
     * @return a 400 problem with an {@code errors} array of {@link FieldViolation}s
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.isForReturnValue()) {
            // The server's fault, not the client's: no field list.
            return super.handleHandlerMethodValidationException(ex, headers, status, request);
        }
        List<FieldViolation> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bean) {
                errors.addAll(violations(bean));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors()
                        .forEach(error -> errors.add(new FieldViolation(name, error.getDefaultMessage())));
            }
        }
        ex.getCrossParameterValidationResults()
                .forEach(error -> errors.add(new FieldViolation("", error.getDefaultMessage())));
        return validationFailed(ex, ex.getBody(), errors, headers, status, request);
    }

    private ResponseEntity<Object> validationFailed(
            Exception ex,
            ProblemDetail body,
            List<FieldViolation> errors,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        body.setType(ProblemType.VALIDATION_FAILED.uri());
        body.setDetail("Request validation failed.");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Lists field errors and object-level errors (class-level or cross-field constraints); the latter have an empty
     * {@code field}, as they concern the whole object.
     *
     * @param errors the binding or parameter errors
     * @return one violation per error, in the order they were found
     */
    private static List<FieldViolation> violations(Errors errors) {
        return errors.getAllErrors().stream()
                .map(error -> new FieldViolation(
                        error instanceof FieldError field ? field.getField() : "", error.getDefaultMessage()))
                .toList();
    }

    /**
     * Returns the name the client used for a parameter.
     *
     * @param parameter the validated parameter
     * @return its {@code @RequestParam} or {@code @PathVariable} name, else its Java name
     */
    private static String parameterName(MethodParameter parameter) {
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && StringUtils.hasText(param.name())) {
            return param.name();
        }
        PathVariable variable = parameter.getParameterAnnotation(PathVariable.class);
        if (variable != null && StringUtils.hasText(variable.name())) {
            return variable.name();
        }
        return parameter.getParameterName();
    }

    /**
     * One invalid field of a request body.
     *
     * @param field   JSON property name, or empty for an error about the whole object
     * @param message why the value was rejected
     */
    public record FieldViolation(String field, String message) {}
}
