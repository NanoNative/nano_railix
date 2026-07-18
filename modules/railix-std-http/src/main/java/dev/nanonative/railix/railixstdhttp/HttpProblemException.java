package dev.nanonative.railix.railixstdhttp;

final class HttpProblemException extends RuntimeException {

    private final String type;
    private final String title;
    private final int status;
    private final String detail;

    HttpProblemException(final String type, final String title, final int status, final String detail) {
        super(detail);
        this.type = requireNonBlank(type, "type");
        this.title = requireNonBlank(title, "title");
        this.status = status;
        this.detail = requireNonBlank(detail, "detail");
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be between 100 and 599");
        }
    }

    String type() {
        return type;
    }

    String title() {
        return title;
    }

    int status() {
        return status;
    }

    String detail() {
        return detail;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
