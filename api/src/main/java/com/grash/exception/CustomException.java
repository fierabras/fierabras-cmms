package com.grash.exception;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.io.Serial;

@Setter
public class CustomException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String message;
    @Getter
    private final HttpStatus httpStatus;
    /**
     * Stable identifier of the rule that rejected the request, for clients that tell errors apart; null if none.
     */
    @Getter
    private final String code;

    public CustomException(String message, HttpStatus httpStatus) {
        this(message, httpStatus, null);
    }

    public CustomException(String message, HttpStatus httpStatus, String code) {
        this.message = message;
        this.httpStatus = httpStatus;
        this.code = code;
    }

    @Override
    public String getMessage() {
        return message;
    }

}
