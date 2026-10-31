package com.logali.rfcguard;

import java.util.Map;

/** A catalog refusal with an HTTP status, a stable code, a readable message and safe details. */
final class CatalogException extends RuntimeException {
  private final int status;
  private final String code;
  private final Map<String, Object> details;

  CatalogException(int status, String code, String message) { this(status, code, message, Map.of()); }

  CatalogException(int status, String code, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  int status() { return status; }
  String code() { return code; }
  Map<String, Object> details() { return details; }
}
