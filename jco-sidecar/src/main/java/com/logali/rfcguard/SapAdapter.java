package com.logali.rfcguard;

import java.util.List;
import java.util.Map;

interface SapAdapter extends AutoCloseable {
  record Backend(String systemId, String client, String host, String release) {}
  void ping();
  Backend backend();
  List<UserRecord> listUsers(Map<String, Object> parameters);
  List<UserRecord> getUser(String username);
  @Override default void close() {}
}
