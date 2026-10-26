package com.logali.rfcguard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class JcoSapAdapterPolicyTest {
  @Test void acceptsTheClientResolvedFromAManagedBtpDestination() {
    assertDoesNotThrow(() -> JcoSapAdapter.assertClientMatches("250", "250"));
  }

  @Test void restrictsLifecycleChangesToThePrefixedGovernedUsers() {
    assertEquals("N8N_DEMO_01", JcoSapAdapter.governedUsername("n8n_demo_01", "N8N_DEMO_"));
    for (String forbidden : new String[] {"DDIC", "SAP*", "N8N_DEMO_TOO_LONG", "", "TRAINING_ADMIN"}) {
      assertEquals("USERNAME_PREFIX_INVALID", assertThrows(IllegalArgumentException.class,
          () -> JcoSapAdapter.governedUsername(forbidden, "N8N_DEMO_")).getMessage(), forbidden);
    }
  }

  @Test void rejectsAClientOutsideTheOperatedDestination() {
    var error = assertThrows(IllegalArgumentException.class,
        () -> JcoSapAdapter.assertClientMatches("100", "250"));
    assertEquals("CLIENT_MISMATCH", error.getMessage());
  }

  @Test void derivesAvailabilityFromConfirmedQuantityInsteadOfAnEmptyDialogFlag() {
    assertEquals("NotAvailable", JcoSapAdapter.availabilityStatus("", "10", "0"));
    assertEquals("PartiallyAvailable", JcoSapAdapter.availabilityStatus("X", "10", "4"));
    assertEquals("FullyAvailable", JcoSapAdapter.availabilityStatus("", "10", "10"));
    assertEquals("NotAvailabilityRelevant", JcoSapAdapter.availabilityStatus("N", "10", "0"));
  }
}
