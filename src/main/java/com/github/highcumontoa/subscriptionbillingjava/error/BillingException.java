package com.github.highcumontoa.subscriptionbillingjava.error;

/**
 * 唯一业务异常类型，通过 errorCode 区分失败/拒绝原因。
 */
public class BillingException extends RuntimeException {
    private final ErrorCode errorCode;

    public BillingException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
