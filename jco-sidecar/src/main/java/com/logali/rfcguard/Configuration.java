package com.logali.rfcguard;

import java.util.Map;

record Configuration(
    int port,
    String apiToken,
    String destinationName,
    int maxRows,
    int inactiveDays,
    int requestTimeoutSeconds,
    String tlsKeystorePath,
    String tlsKeystorePassword,
    String mode,
    boolean userCreationEnabled,
    String userCreatePrefix,
    String userCreateGroup,
    int userCreateMaxValidityDays,
    String newUserInitialPassword,
    Map<String, String> jcoProperties
) {
  Configuration(int port, String apiToken, String destinationName, int maxRows, int inactiveDays,
      int requestTimeoutSeconds, String tlsKeystorePath, String tlsKeystorePassword,
      Map<String, String> jcoProperties) {
    this(port, apiToken, destinationName, maxRows, inactiveDays, requestTimeoutSeconds,
        tlsKeystorePath, tlsKeystorePassword, "read-only", false, "N8N_DEMO_", "",
        7, "", jcoProperties);
  }

  static Configuration fromEnvironment() {
    var env = System.getenv();
    var properties = new java.util.LinkedHashMap<String, String>();
    put(properties, "jco.client.ashost", env.get("SAP_ASHOST"));
    put(properties, "jco.client.sysnr", env.get("SAP_SYSNR"));
    put(properties, "jco.client.client", env.get("SAP_CLIENT"));
    put(properties, "jco.client.user", env.get("SAP_USER"));
    put(properties, "jco.client.passwd", env.get("SAP_PASSWORD"));
    put(properties, "jco.client.lang", env.getOrDefault("SAP_LANG", "EN"));
    put(properties, "jco.client.mshost", env.get("SAP_MSHOST"));
    put(properties, "jco.client.r3name", env.get("SAP_R3NAME"));
    put(properties, "jco.client.group", env.get("SAP_GROUP"));
    put(properties, "jco.destination.pool_capacity", env.getOrDefault("SAP_POOL_CAPACITY", "3"));
    put(properties, "jco.destination.peak_limit", env.getOrDefault("SAP_PEAK_LIMIT", "10"));
    put(properties, "jco.client.snc_mode", env.get("SAP_SNC_MODE"));
    put(properties, "jco.client.snc_partnername", env.get("SAP_SNC_PARTNERNAME"));
    put(properties, "jco.client.snc_qop", env.get("SAP_SNC_QOP"));
    put(properties, "jco.client.snc_myname", env.get("SAP_SNC_MYNAME"));
    put(properties, "jco.client.snc_lib", env.get("SAP_SNC_LIB"));

    var token = require(env, "RFC_GUARD_API_TOKEN");
    var tlsKeystore = require(env, "RFC_GUARD_TLS_KEYSTORE");
    var tlsPassword = require(env, "RFC_GUARD_TLS_KEYSTORE_PASSWORD");
    if (token.length() < 32) throw new IllegalArgumentException("RFC_GUARD_API_TOKEN must contain at least 32 characters");
    if (!properties.containsKey("jco.client.client") || !properties.containsKey("jco.client.user")
        || !properties.containsKey("jco.client.passwd")) {
      throw new IllegalArgumentException("SAP_CLIENT, SAP_USER and SAP_PASSWORD are required");
    }
    boolean direct = properties.containsKey("jco.client.ashost") && properties.containsKey("jco.client.sysnr");
    boolean balanced = properties.containsKey("jco.client.mshost")
        && properties.containsKey("jco.client.r3name") && properties.containsKey("jco.client.group");
    if (!direct && !balanced) {
      throw new IllegalArgumentException("Configure SAP_ASHOST + SAP_SYSNR or SAP_MSHOST + SAP_R3NAME + SAP_GROUP");
    }

    String mode = env.getOrDefault("RFC_GUARD_MODE", "read-only").trim();
    if (!mode.equals("read-only") && !mode.equals("user-provisioning")) {
      throw new IllegalArgumentException("RFC_GUARD_MODE must be read-only or user-provisioning");
    }
    boolean userCreationEnabled = "true".equalsIgnoreCase(
        env.getOrDefault("RFC_GUARD_ENABLE_USER_CREATE", "false"));
    String userCreatePrefix = env.getOrDefault("RFC_GUARD_USER_PREFIX", "N8N_DEMO_").trim().toUpperCase();
    String userCreateGroup = env.getOrDefault("RFC_GUARD_USER_GROUP", "").trim().toUpperCase();
    int userCreateMaxValidityDays = integer(env, "RFC_GUARD_USER_VALIDITY_DAYS", 7, 1, 30);
    String newUserInitialPassword = env.getOrDefault("RFC_GUARD_NEW_USER_PASSWORD", "");
    if (mode.equals("user-provisioning")) {
      if (!userCreationEnabled) {
        throw new IllegalArgumentException("RFC_GUARD_ENABLE_USER_CREATE=true is required in user-provisioning mode");
      }
      if (!userCreatePrefix.matches("[A-Z0-9_]{1,11}")) {
        throw new IllegalArgumentException("RFC_GUARD_USER_PREFIX must contain 1-11 uppercase letters, numbers, or underscores");
      }
      if (!userCreateGroup.matches("[A-Z0-9_]{1,12}")) {
        throw new IllegalArgumentException("RFC_GUARD_USER_GROUP is required and must be a valid SAP user group");
      }
      if (newUserInitialPassword.length() < 12 || newUserInitialPassword.length() > 40) {
        throw new IllegalArgumentException("RFC_GUARD_NEW_USER_PASSWORD must contain 12-40 characters");
      }
    }

    return new Configuration(
        integer(env, "PORT", 8080, 1, 65535), token,
        env.getOrDefault("SAP_DESTINATION_NAME", "SAP_RFC_GUARD"),
        integer(env, "RFC_GUARD_MAX_ROWS", 50, 1, 500),
        integer(env, "RFC_GUARD_INACTIVE_DAYS", 90, 1, 3650),
        integer(env, "RFC_GUARD_REQUEST_TIMEOUT_SECONDS", 30, 1, 300),
        tlsKeystore, tlsPassword, mode, userCreationEnabled, userCreatePrefix,
        userCreateGroup, userCreateMaxValidityDays, newUserInitialPassword,
        Map.copyOf(properties));
  }

  private static void put(Map<String, String> target, String key, String value) {
    if (value != null && !value.isBlank()) target.put(key, value.trim());
  }

  private static String require(Map<String, String> env, String key) {
    var value = env.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
    return value;
  }

  private static int integer(Map<String, String> env, String key, int fallback, int min, int max) {
    int value = Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback)));
    if (value < min || value > max) throw new IllegalArgumentException(key + " is outside the permitted range");
    return value;
  }
}
