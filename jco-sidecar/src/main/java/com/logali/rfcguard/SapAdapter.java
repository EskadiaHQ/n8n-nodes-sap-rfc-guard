package com.logali.rfcguard;

import java.util.List;
import java.util.Map;

interface SapAdapter extends AutoCloseable {
  record Backend(String systemId, String client, String host, String release) {}
  void ping();
  Backend backend();
  List<UserRecord> listUsers(Map<String, Object> parameters);
  List<UserRecord> getUser(String username);
  default List<Map<String, Object>> executeBusinessRead(
      String operation, Map<String, Object> parameters) {
    throw new IllegalArgumentException("OPERATION_NOT_ALLOWED");
  }
  default Map<String, Object> createCommunicationUser(Map<String, Object> parameters) {
    throw new IllegalArgumentException("USER_CREATION_DISABLED");
  }
  @Override default void close() {}
}
