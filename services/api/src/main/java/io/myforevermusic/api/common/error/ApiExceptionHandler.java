package io.myforevermusic.api.common.error;

import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService;
import io.myforevermusic.api.common.errorlog.ApplicationErrorLogService.RecordExceptionCommand;
import io.myforevermusic.api.modules.auth.application.AuthEmailAlreadyRegisteredException;
import io.myforevermusic.api.modules.auth.application.AuthInvalidCredentialsException;
import io.myforevermusic.api.modules.platform.application.PlatformProviderOperationException;
import io.myforevermusic.api.modules.platform.application.PlatformReconnectRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final Optional<ApplicationErrorLogService> errorLogService;

    public ApiExceptionHandler(Optional<ApplicationErrorLogService> errorLogService) {
        this.errorLogService = errorLogService;
    }

    @ExceptionHandler(AuthEmailAlreadyRegisteredException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailConflict(
        AuthEmailAlreadyRegisteredException exception,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.CONFLICT, null, exception.getMessage(), List.of(), exception, request);
    }

    @ExceptionHandler(AuthInvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(
        AuthInvalidCredentialsException exception,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.UNAUTHORIZED,
            "invalid_credentials",
            exception.getMessage(),
            List.of(),
            exception,
            request
        );
    }

    @ExceptionHandler(ApiResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
        ApiResourceNotFoundException exception,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.NOT_FOUND, null, exception.getMessage(), List.of(), exception, request);
    }

    @ExceptionHandler(PlatformReconnectRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handlePlatformReconnectRequired(
        PlatformReconnectRequiredException exception,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.CONFLICT,
            "platform_reconnect_required",
            exception.getMessage(),
            List.of(),
            exception,
            request
        );
    }

    @ExceptionHandler(PlatformProviderOperationException.class)
    public ResponseEntity<ApiErrorResponse> handlePlatformProviderOperation(
        PlatformProviderOperationException exception,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.BAD_GATEWAY,
            "platform_provider_operation_failed",
            exception.getMessage(),
            List.of(),
            exception,
            request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationError(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        List<ApiErrorResponse.FieldIssue> issues = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(this::toFieldIssue)
            .toList();

        String message = issues.isEmpty()
            ? "Validation failed for request body."
            : issues.getFirst().message();

        return buildResponse(HttpStatus.BAD_REQUEST, null, message, issues, exception, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        return buildResponse(HttpStatus.BAD_REQUEST, null, exception.getMessage(), List.of(), exception, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
        HttpMessageNotReadableException exception,
        HttpServletRequest request
    ) {
        return buildResponse(
            HttpStatus.BAD_REQUEST,
            null,
            "Request body could not be read.",
            List.of(),
            exception,
            request
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
        ResponseStatusException exception,
        HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        HttpStatus resolvedStatus = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        String message = exception.getReason() == null ? resolvedStatus.getReasonPhrase() : exception.getReason();
        return buildResponse(resolvedStatus, null, message, List.of(), exception, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        return buildResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal_server_error",
            exception.getMessage() == null ? "Unexpected server error." : exception.getMessage(),
            List.of(),
            exception,
            request
        );
    }

    private ApiErrorResponse.FieldIssue toFieldIssue(FieldError error) {
        return new ApiErrorResponse.FieldIssue(
            error.getField(),
            error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage()
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
        HttpStatus status,
        String code,
        String message,
        List<ApiErrorResponse.FieldIssue> issues,
        Exception exception,
        HttpServletRequest request
    ) {
        recordError(status, code, message, exception, request);
        return ResponseEntity.status(status).body(
            new ApiErrorResponse(
                "api",
                "error",
                code,
                message,
                Instant.now(),
                issues.isEmpty() ? null : issues
            )
        );
    }

    private void recordError(
        HttpStatus status,
        String code,
        String message,
        Exception exception,
        HttpServletRequest request
    ) {
        errorLogService.ifPresent(service -> service.recordException(new RecordExceptionCommand(
            sourceFor(request),
            null,
            "api",
            status.value(),
            code,
            message,
            exception,
            request.getMethod(),
            request.getRequestURI(),
            request.getParameter("user_id"),
            request.getHeader("X-Request-Id"),
            null
        )));
    }

    private String sourceFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/platforms") || path.contains("/playback/")) {
            return "platform";
        }
        if (path.startsWith("/api/v1/ems")) {
            return "ems";
        }
        if (path.startsWith("/api/v1/gms")) {
            return "gms";
        }
        if (path.startsWith("/api/v1/pms")) {
            return "pms";
        }
        if (path.startsWith("/api/v1/public-curations")) {
            return "public-curation";
        }
        if (path.startsWith("/api/v1/recommendations")) {
            return "recommendation";
        }
        if (path.startsWith("/api/v1/admin/melon") || path.contains("melon-hot-100")) {
            return "melon";
        }
        return "api";
    }
}
