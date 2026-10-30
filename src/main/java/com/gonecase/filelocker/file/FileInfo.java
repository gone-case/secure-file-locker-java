package com.gonecase.filelocker.file;

import java.time.Instant;

/** sha256 is only known at upload time (S3 listings don't return object metadata). */
public record FileInfo(String id, String name, long size, Instant uploadedAt, String sha256) {}
