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

  @Test void rejectsUnexpectedParametersBeforeCallingSap() {
    var service = new GuardService(new StubAdapter());
    var error = assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("rfcFunction", "RFC_READ_TABLE")));
    assertEquals("PARAMETER_NOT_ALLOWED", error.getMessage());
  }

  @Test void rejectsInvalidFiltersAndBounds() {
    var service = new GuardService(new StubAdapter());
    assertEquals("MAX_ROWS_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("maxRows", 0))).getMessage());
    assertEquals("INACTIVE_DAYS_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("inactiveDays", "many"))).getMessage());
    assertEquals("USER_TYPE_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("userType", "Administrator"))).getMessage());
    assertEquals("DIMENSION_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("summarizeSu01Accounts", Map.of("dimension", "email"))).getMessage());
  }

  @Test void requiresAValidUsernameForDetail() {
    var service = new GuardService(new StubAdapter());
    assertEquals("USERNAME_REQUIRED", assertThrows(IllegalArgumentException.class,
        () -> service.execute("getSu01UserDetail", Map.of())).getMessage());
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
