package com.hikmethankolay.template.common;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * Domain error that is rendered as an RFC 9457 {@code application/problem+json} response.
 *
 * <p>Throw it from services through the static factories; {@link GlobalExceptionHandler}
 * turns it into a response with the given status, problem type, detail message and headers.</p>
 */
public class ApiException extends ErrorResponseException {

    /**
     * Creates an exception for the given status.
     *
     * @param status HTTP status of the response
     * @param type   the problem type, sent to the client as {@code type}
     * @param detail human-readable explanation, sent to the client as {@code detail}
     */
    public ApiException(HttpStatus status, ProblemType type, String detail) {
        super(status, type.problem(status, detail), null);
    }

    /**
     * Creates a {@code 400 Bad Request} error.
     *
     * @param type   the problem type
     * @param detail explanation sent to the client
     * @return the exception to throw
     */
    public static ApiException badRequest(ProblemType type, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, type, detail);
    }

    /**
     * Creates a {@code 404 Not Found} error.
     *
     * @param type   the problem type
     * @param detail explanation sent to the client
     * @return the exception to throw
     */
    public static ApiException notFound(ProblemType type, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, type, detail);
    }

    /**
     * Creates a {@code 409 Conflict} error, e.g. for a duplicate username.
     *
     * @param type   the problem type
     * @param detail explanation sent to the client
     * @return the exception to throw
     */
    public static ApiException conflict(ProblemType type, String detail) {
        return new ApiException(HttpStatus.CONFLICT, type, detail);
    }

    /**
     * Creates a {@code 412 Precondition Failed} error, e.g. for an {@code If-Match} header that no longer matches.
     *
     * @param type   the problem type
     * @param detail explanation sent to the client
     * @return the exception to throw
     */
    public static ApiException preconditionFailed(ProblemType type, String detail) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, type, detail);
    }

    /**
     * Creates a {@code 429 Too Many Requests} error with a {@code Retry-After} header.
     *
     * @param type       the problem type
     * @param detail     explanation sent to the client
     * @param retryAfter how long the client should wait before retrying
     * @return the exception to throw
     */
    public static ApiException tooManyRequests(ProblemType type, String detail, Duration retryAfter) {
        ApiException exception = new ApiException(HttpStatus.TOO_MANY_REQUESTS, type, detail);
        exception.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter.toSeconds()));
        return exception;
    }
}
