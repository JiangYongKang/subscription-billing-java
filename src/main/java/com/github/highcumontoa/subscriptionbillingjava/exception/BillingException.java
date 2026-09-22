package com.github.highcumontoa.subscriptionbillingjava.exception;

/** 本地计费域统一异常：携带可区分的 ErrorCode 与可读说明。 */
public class BillingException extends RuntimeException {

    private final ErrorCode code;

    public BillingException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
