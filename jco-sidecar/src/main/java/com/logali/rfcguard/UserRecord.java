package com.logali.rfcguard;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

record UserRecord(
    String username,
    String userType,
    String fullName,
    String email,
    String createdAt,
    String validFrom,
    String validTo,
    String lastLogonAt,
    String lockStatus,
    String accountStatus,
    String riskReason
) {
  Map<String, Object> toMap(boolean includeRisk) {
    var output = new LinkedHashMap<String, Object>();
    output.put("username", username);
    output.put("userType", userType);
    output.put("fullName", fullName);
    output.put("email", email);
    output.put("createdAt", createdAt);
    output.put("validFrom", validFrom);
    output.put("validTo", validTo);
    output.put("lastLogonAt", lastLogonAt);
    output.put("lockStatus", lockStatus);
    output.put("accountStatus", accountStatus);
    if (includeRisk && riskReason != null && !riskReason.isBlank()) output.put("riskReason", riskReason);
    return output;
  }

  static UserRecord classify(Map<String, String> raw, int inactiveDays, LocalDate today) {
    String username = value(raw, "username").toUpperCase();
    String userType = mapUserType(value(raw, "userType"));
    String validFrom = date(value(raw, "validFrom"));
    String validTo = date(value(raw, "validTo"));
    String lastLogon = date(value(raw, "lastLogonAt"));
    String lockStatus = lockStatus(raw);

    String status = "Active";
    String reason = "";
    var from = parseDate(validFrom);
    var to = parseDate(validTo);
    var last = parseDate(lastLogon);
    if (!"Unlocked".equals(lockStatus)) {
      status = "Locked";
      reason = "account_locked";
    } else if (to != null && to.isBefore(today)) {
      status = "Expired";
      reason = "validity_expired";
    } else if (from != null && from.isAfter(today)) {
      status = "NotYetValid";
      reason = "validity_not_started";
    } else if ("Dialog".equals(userType) && (last == null || last.isBefore(today.minusDays(inactiveDays)))) {
      status = "Inactive";
      reason = last == null ? "never_logged_on" : "no_recent_logon";
    }
    return new UserRecord(username, userType, value(raw, "fullName"), value(raw, "email"),
        date(value(raw, "createdAt")), validFrom, validTo, lastLogon, lockStatus, status, reason);
  }

  private static String lockStatus(Map<String, String> raw) {
    if (flag(raw, "globalLock")) return "GloballyLocked";
    if (flag(raw, "localLock")) return "LockedByAdministrator";
    if (flag(raw, "wrongLogonLock")) return "LockedByFailedLogons";
    if (flag(raw, "noPassword")) return "NoPassword";
    return "Unlocked";
  }

  private static boolean flag(Map<String, String> raw, String name) {
    String value = value(raw, name);
    return "X".equalsIgnoreCase(value) || "1".equals(value) || "TRUE".equalsIgnoreCase(value);
  }

  private static String mapUserType(String code) {
    return switch (code.toUpperCase()) {
      case "A", "DIALOG" -> "Dialog";
      case "B", "SYSTEM" -> "System";
      case "C", "COMMUNICATION" -> "Communication";
      case "L", "REFERENCE" -> "Reference";
      case "S", "SERVICE" -> "Service";
      default -> code.isBlank() ? "Unknown" : code;
    };
  }

  private static String date(String input) {
    if (input == null || input.isBlank() || "00000000".equals(input) || "0000-00-00".equals(input)) return "";
    String digits = input.replaceAll("[^0-9]", "");
    if (digits.length() >= 8) return digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8);
    return input;
  }

  private static LocalDate parseDate(String value) {
    try { return value == null || value.isBlank() ? null : LocalDate.parse(value); }
    catch (RuntimeException ignored) { return null; }
  }

  private static String value(Map<String, String> source, String key) {
    return source.getOrDefault(key, "").trim();
  }
}
