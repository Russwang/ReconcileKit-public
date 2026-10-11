package dev.reconcilekit.api;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiException invalid(String message) { return new ApiException(422, "INVALID_INPUT", message); }
    public static ApiException structure(String message) { return new ApiException(400, "INVALID_REQUEST", message); }
    public static ApiException conflict(String message) { return new ApiException(409, "BUSINESS_CONFLICT", message); }
    public static ApiException missing(String message) { return new ApiException(404, "NOT_FOUND", message); }
}
