package com.logali.rfcguard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

final class UserRecordTest {
  @Test void classifiesLockedBeforeExpired() {
    var raw = base();
    raw.put("globalLock", "X");
    raw.put("validTo", "20200101");
    var user = UserRecord.classify(raw, 90, LocalDate.of(2026, 8, 21));
    assertEquals("Locked", user.accountStatus());
    assertEquals("account_locked", user.riskReason());
  }

  @Test void classifiesDormantDialogUser() {
    var raw = base();
    raw.put("lastLogonAt", "20260101");
    var user = UserRecord.classify(raw, 90, LocalDate.of(2026, 8, 21));
    assertEquals("Dialog", user.userType());
    assertEquals("Inactive", user.accountStatus());
    assertEquals("2026-01-01", user.lastLogonAt());
  }

  @Test void doesNotClassifyTechnicalUserByDialogDormancyRule() {
    var raw = base();
    raw.put("userType", "B");
    raw.put("lastLogonAt", "");
    assertEquals("Active", UserRecord.classify(raw, 90, LocalDate.of(2026, 8, 21)).accountStatus());
  }

  private static HashMap<String, String> base() {
    var raw = new HashMap<String, String>();
    raw.put("username", "TEST_USER");
    raw.put("userType", "A");
    raw.put("validFrom", "20250101");
    raw.put("validTo", "99991231");
    raw.put("lastLogonAt", "20260820");
    return raw;
  }
}
