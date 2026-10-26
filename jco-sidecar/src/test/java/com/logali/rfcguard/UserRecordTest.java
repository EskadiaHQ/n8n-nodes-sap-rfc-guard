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

  @Test void classifiesIsoTimestampUsingItsDate() {
    var raw = base();
    raw.put("lastLogonAt", "2026-01-01T23:59:58");
    var user = UserRecord.classify(raw, 90, LocalDate.of(2026, 8, 21));
    assertEquals("Inactive", user.accountStatus());
    assertEquals("2026-01-01T23:59:58", user.lastLogonAt());
  }

  @Test void formatsSapDateAndOptionalTimeWithoutAssumingUtc() {
    assertEquals("2026-08-20T08:42:03", JcoSapAdapter.timestamp("20260820", "084203"));
    assertEquals("2026-08-20", JcoSapAdapter.timestamp("20260820", ""));
    assertEquals("", JcoSapAdapter.timestamp("00000000", "084203"));
  }

  @Test void normalizesSapAndJcoDateFormats() {
    assertEquals("2026-08-07", JcoSapAdapter.normalizeDate("20260807"));
    assertEquals("2026-08-07", JcoSapAdapter.normalizeDate("2026-08-07"));
    assertEquals("2026-08-07", JcoSapAdapter.normalizeDate("07.08.2026"));
    assertEquals("2026-08-07", JcoSapAdapter.normalizeDate("08/07/2026"));
    assertEquals("", JcoSapAdapter.normalizeDate("0000-00-00"));
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

  @Test void readsTheLockCodesReturnedBySapBapiUserGetDetail() {
    var today = java.time.LocalDate.of(2026, 10, 26);
    java.util.function.Function<String[], String> status = flags -> {
      var raw = new java.util.LinkedHashMap<String, String>();
      raw.put("username", "N8N_ADM_1026"); raw.put("userType", "C");
      raw.put("validFrom", "20261026"); raw.put("validTo", "20261026");
      raw.put("globalLock", flags[0]); raw.put("localLock", flags[1]);
      raw.put("wrongLogonLock", flags[2]); raw.put("noPassword", flags[3]);
      return UserRecord.classify(raw, 90, today).lockStatus();
    };
    org.junit.jupiter.api.Assertions.assertEquals("Unlocked", status.apply(new String[] {"U", "U", "U", "U"}));
    org.junit.jupiter.api.Assertions.assertEquals("LockedByAdministrator", status.apply(new String[] {"U", "L", "U", "U"}));
    org.junit.jupiter.api.Assertions.assertEquals("GloballyLocked", status.apply(new String[] {"L", "U", "U", "U"}));
    org.junit.jupiter.api.Assertions.assertEquals("LockedByFailedLogons", status.apply(new String[] {"U", "U", "L", "U"}));
    org.junit.jupiter.api.Assertions.assertEquals("NoPassword", status.apply(new String[] {"U", "U", "U", "L"}));
    org.junit.jupiter.api.Assertions.assertEquals("LockedByAdministrator", status.apply(new String[] {"", "X", "", ""}));
  }
}
