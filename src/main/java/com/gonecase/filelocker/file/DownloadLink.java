package com.gonecase.filelocker.file;

import java.time.Instant;

public record DownloadLink(String url, Instant expiresAt) {}
