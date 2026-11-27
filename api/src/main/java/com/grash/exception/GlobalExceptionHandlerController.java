package com.grash.exception;

import com.grash.dto.ConflictResponse;
import com.grash.dto.SuccessResponse;
import com.grash.model.Labor;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.WebRequest;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.xml.bind.ValidationException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandlerController {

    @Bean
    public ErrorAttributes errorAttributes() {
        // Hide exception field in the return object
        return new DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
                return super.getErrorAttributes(webRequest,
                        ErrorAttributeOptions.defaults().excluding(ErrorAttributeOptions.Include.EXCEPTION));
            }
        };
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<SuccessResponse> handleCustomException(HttpServletResponse res, CustomException ex) {
        ex.printStackTrace();
        if (ex instanceof ConflictException conflict) {
            return new ResponseEntity<>(new ConflictResponse(conflict.getMessage(), conflict.getCode(),
                    conflict.getBlockingTypes()), conflict.getHttpStatus());
        }
        return new ResponseEntity<>(new SuccessResponse(false, ex.getMessage(), ex.getCode()), ex.getHttpStatus());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<SuccessResponse> handleAccessDeniedException(HttpServletResponse res) {
        return new ResponseEntity<>(new SuccessResponse(false, "Access is denied"), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<SuccessResponse> handleHttpRequestMethodNotSupportedException(HttpServletResponse res,
                                                                                        Exception ex) {
        return new ResponseEntity<>(new SuccessResponse(false, ex.getMessage()), HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ValidationException.class)
    ResponseEntity<SuccessResponse> handleValidationException(ValidationException ex) {
        return new ResponseEntity<>(new SuccessResponse(false, ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ResponseBody
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<SuccessResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        return new ResponseEntity<>(new SuccessResponse(false, ex.getMessage()), HttpStatus.BAD_REQUEST);
    }

    /**
     * P-20: the unique folio of an asset, which a user can write by hand.
     */
    private static final String ASSET_FOLIO_CONSTRAINT = "uq_asset_company_custom_id";

    /**
     * Any other failure answers 500, except a foreign key that rejects a deletion (PPB-26): that is a 409
     * {@code RECORD_IN_USE} without SQL or constraint names. Deletions with a rule answer their own code before
     * reaching here ({@code DeletionService}). An asset folio already taken (P-20) is a 409
     * {@code ASSET_FOLIO_TAKEN}: the controller checks it first; this covers two simultaneous requests. A labor entry
     * that would give a technician a second timer on a work order (P-21) is a 409 {@code LABOR_TIMER_TAKEN}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<SuccessResponse> handleException(HttpServletResponse res, Exception ex) {
        ex.printStackTrace();
        if (isDeletion() && ForeignKeyViolation.in(ex).isPresent()) {
            return conflict(ConflictException.recordInUse());
        }
        if (UniqueViolation.in(ex).filter(violation -> ASSET_FOLIO_CONSTRAINT.equals(violation.constraint()))
                .isPresent()) {
            return conflict(ConflictException.assetFolioTaken());
        }
        if (UniqueViolation.in(ex).filter(violation -> Labor.TIMER_INDEX.equals(violation.constraint()))
                .isPresent()) {
            return conflict(ConflictException.laborTimerTaken());
        }
        return new ResponseEntity<>(new SuccessResponse(false, ex.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<SuccessResponse> conflict(ConflictException conflict) {
        return new ResponseEntity<>(new ConflictResponse(conflict.getMessage(), conflict.getCode(), null),
                HttpStatus.CONFLICT);
    }

    private static boolean isDeletion() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && "DELETE".equalsIgnoreCase(attributes.getRequest().getMethod());
    }

}

