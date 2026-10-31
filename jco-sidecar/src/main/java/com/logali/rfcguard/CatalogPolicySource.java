package com.logali.rfcguard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Re-reads the mounted policy whenever it changes, so every request is checked against the policy
 * that is on disk now. An unreadable or invalid file disables the catalog (fail closed).
 */
final class CatalogPolicySource {
  private final Path path;
  private FileTime modified;
  private long size = -1;
  private CatalogPolicy policy;
  private String failure;

  CatalogPolicySource(Path path) { this.path = path; }

  synchronized CatalogPolicy current() {
    try {
      FileTime currentModified = Files.getLastModifiedTime(path);
      long currentSize = Files.size(path);
      if (!currentModified.equals(modified) || currentSize != size) {
        modified = currentModified;
        size = currentSize;
        try {
          policy = CatalogPolicy.parse(Files.readAllBytes(path));
          failure = null;
        } catch (IllegalArgumentException error) {
          policy = null;
          failure = error.getMessage();
          System.err.println("RFC_GUARD_CATALOG_POLICY_REJECTED " + failure);
        }
      }
    } catch (java.io.IOException error) {
      policy = null;
      modified = null;
      size = -1;
      failure = "CATALOG_POLICY_INVALID: the policy file cannot be read";
    }
    if (policy == null) throw new CatalogException(503, "CATALOG_POLICY_INVALID", failure == null
        ? "The function catalog policy is unavailable." : failure.replace("CATALOG_POLICY_INVALID: ", "The function catalog policy was rejected: ") + ".");
    return policy;
  }

  /** Health view that never throws. */
  synchronized String failure() {
    try { current(); return null; }
    catch (CatalogException error) { return error.getMessage(); }
  }
}
