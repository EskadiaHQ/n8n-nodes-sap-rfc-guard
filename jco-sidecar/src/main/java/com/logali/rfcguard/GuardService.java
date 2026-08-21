package com.logali.rfcguard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GuardService {
  static final Set<String> OPERATIONS = Set.of(
      "listSu01Users", "getSu01UserDetail", "listSu01RiskAccounts", "summarizeSu01Accounts");
  private final SapAdapter adapter;

  GuardService(SapAdapter adapter) { this.adapter = adapter; }

  List<Map<String, Object>> execute(String operation, Map<String, Object> parameters) {
    return switch (operation) {
      case "listSu01Users" -> maps(adapter.listUsers(parameters), false);
      case "getSu01UserDetail" -> maps(adapter.getUser(requiredUsername(parameters)), false);
      case "listSu01RiskAccounts" -> maps(adapter.listUsers(parameters).stream()
          .filter(user -> !"Active".equals(user.accountStatus())).toList(), true);
      case "summarizeSu01Accounts" -> summarize(adapter.listUsers(parameters), parameters);
      default -> throw new IllegalArgumentException("OPERATION_NOT_ALLOWED");
    };
  }

  private static String requiredUsername(Map<String, Object> parameters) {
    Object username = parameters.get("username");
    if (username == null || username.toString().isBlank()) throw new IllegalArgumentException("USERNAME_REQUIRED");
    return username.toString().trim();
  }

  private static List<Map<String, Object>> maps(List<UserRecord> users, boolean includeRisk) {
    return users.stream().map(user -> user.toMap(includeRisk)).toList();
  }

  private static List<Map<String, Object>> summarize(List<UserRecord> users, Map<String, Object> parameters) {
    String dimension = "userType".equals(parameters.get("dimension")) ? "userType" : "accountStatus";
    var counts = new java.util.TreeMap<String, Integer>();
    for (var user : users) {
      String value = "userType".equals(dimension) ? user.userType() : user.accountStatus();
      counts.merge(value, 1, Integer::sum);
    }
    var output = new ArrayList<Map<String, Object>>();
    counts.forEach((value, count) -> {
      var row = new LinkedHashMap<String, Object>();
      row.put("dimension", dimension);
      row.put("value", value);
      row.put("count", count);
      output.add(row);
    });
    output.sort(Comparator.comparing(item -> item.get("value").toString()));
    return output;
  }
}
