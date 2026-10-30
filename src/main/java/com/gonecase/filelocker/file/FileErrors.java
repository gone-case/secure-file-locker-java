package com.gonecase.filelocker.file;

public final class FileErrors {
    private FileErrors() {}

    public static class NotFound extends RuntimeException {
        public NotFound(String id) { super("file not found: " + id); }
    }

    public static class BadRequest extends RuntimeException {
        public BadRequest(String msg) { super(msg); }
    }
}
