package com.logali.rfcguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GuardServiceTest {
  @Test void exposesOnlyBusinessAliases() {
    var service = new GuardService(new StubAdapter());
    assertThrows(IllegalArgumentException.class, () -> service.execute("BAPI_USER_GETLIST", Map.of()));
  }

  @Test void summarizesWithoutReturningRawUsers() {
    var rows = new GuardService(new StubAdapter()).execute("summarizeSu01Accounts", Map.of());
    assertEquals(2, rows.size());
    assertEquals("accountStatus", rows.getFirst().get("dimension"));
  }

  @Test void returnsOnlyRiskAccounts() {
    var rows = new GuardService(new StubAdapter()).execute("listSu01RiskAccounts", Map.of());
    assertEquals(1, rows.size());
    assertEquals("account_locked", rows.getFirst().get("riskReason"));
  }

  private static final class StubAdapter implements SapAdapter {
    @Override public void ping() {}
    @Override public Backend backend() { return new Backend("S4D", "100", "sap", "2025"); }
    @Override public List<UserRecord> listUsers(Map<String, Object> ignored) {
      return List.of(user("ACTIVE", "Active", ""), user("LOCKED", "Locked", "account_locked"));
    }
    @Override public List<UserRecord> getUser(String username) { return List.of(user(username, "Active", "")); }
    private UserRecord user(String username, String status, String reason) {
      return new UserRecord(username, "Dialog", "", "", "", "", "", "", "Unlocked", status, reason);
    }
  }
}
