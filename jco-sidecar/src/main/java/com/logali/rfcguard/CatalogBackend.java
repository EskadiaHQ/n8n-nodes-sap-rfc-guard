package com.logali.rfcguard;

import java.util.List;
import java.util.Map;

/** SAP access used by the function catalog; implemented with JCo, stubbed only in unit tests. */
interface CatalogBackend {
  record Field(String name, String type, int length, int decimals, String description,
      String recordType, List<Field> fields) {}

  record Parameter(String name, String direction, String type, int length, int decimals,
      boolean optional, String defaultValue, String description, String recordType,
      List<Field> fields) {}

  record Signature(String name, List<Parameter> parameters, List<String> exceptions) {
    Parameter parameter(String name) {
      return parameters.stream().filter(item -> item.name().equals(name)).findFirst().orElse(null);
    }
  }

  record RemoteInfo(boolean remoteEnabled, boolean updateTask) {}

  record SearchHit(String name, String group, String shortText) {}

  record BorMethod(String objectType, String method, String objectName, String shortText,
      boolean released) {}

  /** Values already validated and converted against the real signature. */
  record CallRequest(String function, Map<String, Object> imports, Map<String, Object> changing,
      Map<String, Object> tables, List<String> outputs, int maxRows, boolean rollbackAfterCall) {}

  record CallResult(Map<String, Object> exports, Map<String, Object> changing,
      Map<String, Object> tables, Map<String, Integer> tableRows, String transaction) {}

  /** A JCo failure classified as transport, SAP authorization, ABAP exception or ABAP runtime. */
  final class SapCallException extends RuntimeException {
    private final String category;
    private final String code;
    private final String sapKey;

    SapCallException(String category, String code, String sapKey, String message) {
      super(message);
      this.category = category;
      this.code = code;
      this.sapKey = sapKey;
    }

    String category() { return category; }
    String code() { return code; }
    String sapKey() { return sapKey; }
  }

  SapAdapter.Backend backend();
  /** Real interface from the JCo repository (RFC_METADATA_GET); null when SAP has no such function. */
  Signature signature(String function);
  /** RFC_GET_FUNCTION_INTERFACE: whether SAP exposes the function remotely; null when missing. */
  RemoteInfo remoteInfo(String function);
  /** RFC_FUNCTION_SEARCH. */
  List<SearchHit> search(String pattern, int maxResults);
  /** SWO_QUERY_API_METHODS, keyed by function module. */
  Map<String, List<BorMethod>> borMethods();
  /** BAPI_INTERFACE_GETDOCU as plain text lines. */
  List<String> bapiDocumentation(String objectType, String method, int maxLines);
  CallResult call(CallRequest request);
}
