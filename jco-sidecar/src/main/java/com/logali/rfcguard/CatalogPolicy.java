package com.logali.rfcguard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side allowlist for the dynamic function catalog. Nothing is executable unless a reviewed
 * entry declares it, and this release accepts only functions whose declared effect is a read.
 */
record CatalogPolicy(String sha256, boolean metadataSearch, Map<String, FunctionPolicy> functions) {
  static final Pattern FUNCTION_NAME = Pattern.compile("[A-Z0-9_/]{1,30}");
  static final Pattern PARAMETER_NAME = Pattern.compile("[A-Z0-9_/]{1,30}");
  static final int MAX_FUNCTIONS = 200;
  /**
   * Generic executors and session controls that would turn the catalog into an arbitrary RFC
   * gateway, plus families that already have a dedicated governed flow in this sidecar.
   */
  static final Set<String> FORBIDDEN = Set.of(
      "RFC_READ_TABLE", "BBP_RFC_READ_TABLE", "RFC_GET_TABLE_ENTRIES", "TABLE_ENTRIES_GET_VIA_RFC",
      "RFC_ABAP_INSTALL_AND_RUN", "SXPG_COMMAND_EXECUTE", "SXPG_CALL_SYSTEM",
      "BAPI_TRANSACTION_COMMIT", "BAPI_TRANSACTION_ROLLBACK", "RFC_METADATA_GET",
      "RFC_FUNCTION_SEARCH", "SWO_QUERY_API_METHODS", "BAPI_INTERFACE_GETDOCU",
      "RFC_GET_FUNCTION_INTERFACE", "RPY_FUNCTIONMODULE_READ", "RPY_FUNCTIONMODULE_READ_NEW");
  static final List<String> FORBIDDEN_PREFIXES = List.of(
      "BAPI_USER_", "SUSR_", "PRGN_", "RFC_ABAP_", "SXPG_", "TH_", "SYSTEM_");

  enum Kind { BAPI, RFC }

  record InputRule(boolean required, Pattern pattern, List<String> allowedValues, boolean alphaPad,
      int maxRows, Set<String> fields) {}

  record FunctionPolicy(String name, String title, String description, Kind kind,
      String borObjectType, String borMethod, String effect, String transaction,
      boolean allowMetadata, boolean allowExecute, Map<String, InputRule> inputs,
      Map<String, String> fixed, List<String> outputs, String messageParameter, int maxRows,
      List<String> businessAuthorizations, String approvedBy, String approvedOn,
      String reviewReference, List<String> tags) {
    boolean rollbackAfterCall() { return "rollback-after-call".equals(transaction); }
  }

  static boolean forbidden(String name) {
    return FORBIDDEN.contains(name) || FORBIDDEN_PREFIXES.stream().anyMatch(name::startsWith);
  }

  static CatalogPolicy parse(byte[] content) {
    JsonNode root;
    try { root = new ObjectMapper().readTree(content); }
    catch (Exception error) { throw invalid("the policy is not valid JSON"); }
    if (root == null || !root.isObject()) throw invalid("the policy must be a JSON object");
    allowedKeys(root, "policy", Set.of("version", "description", "metadataSearch", "functions"));
    if (root.path("version").asInt(-1) != 1) throw invalid("version must be 1");
    JsonNode functions = root.path("functions");
    if (!functions.isObject()) throw invalid("functions must be an object");
    if (functions.size() > MAX_FUNCTIONS) throw invalid("at most " + MAX_FUNCTIONS + " functions are supported");
    var parsed = new LinkedHashMap<String, FunctionPolicy>();
    var names = functions.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!FUNCTION_NAME.matcher(name).matches()) throw invalid(name + " is not a function module name");
      if (forbidden(name)) throw invalid(name + " is a generic executor or has a dedicated flow and cannot be catalogued");
      parsed.put(name, function(name, functions.get(name)));
    }
    return new CatalogPolicy(sha256(content), bool(root, "metadataSearch", false), Map.copyOf(parsed));
  }

  private static FunctionPolicy function(String name, JsonNode node) {
    if (!node.isObject()) throw invalid(name + " must be an object");
    allowedKeys(node, name, Set.of("title", "description", "kind", "bor", "effect", "transaction",
        "allowMetadata", "allowExecute", "inputs", "fixed", "outputs", "messageParameter", "maxRows",
        "businessAuthorizations", "approval", "tags"));
    Kind kind;
    try { kind = Kind.valueOf(text(node, "kind", name)); }
    catch (IllegalArgumentException error) { throw invalid(name + ".kind must be BAPI or RFC"); }
    String objectType = "";
    String method = "";
    if (kind == Kind.BAPI) {
      JsonNode bor = node.path("bor");
      if (!bor.isObject()) throw invalid(name + ".bor is required for a BAPI (object type and method)");
      objectType = text(bor, "objectType", name + ".bor").toUpperCase();
      method = text(bor, "method", name + ".bor").toUpperCase();
      if (!objectType.matches("[A-Z0-9_/]{1,10}") || !method.matches("[A-Z0-9_]{1,32}")) {
        throw invalid(name + ".bor must contain a BOR object type and method name");
      }
    }
    String effect = text(node, "effect", name);
    if ("write".equals(effect)) {
      throw invalid(name + " declares effect=write; this release executes approved reads only");
    }
    if (!"read".equals(effect)) throw invalid(name + ".effect must be read");
    String transaction = text(node, "transaction", name);
    if (!Set.of("none", "rollback-after-call").contains(transaction)) {
      throw invalid(name + ".transaction must be none or rollback-after-call; reads are never committed");
    }
    boolean allowMetadata = bool(node, "allowMetadata", false);
    boolean allowExecute = bool(node, "allowExecute", false);
    var inputs = inputs(name, node.path("inputs"));
    var fixed = fixed(name, node.path("fixed"));
    for (String parameter : fixed.keySet()) {
      if (inputs.containsKey(parameter)) throw invalid(name + "." + parameter + " cannot be both fixed and an input");
    }
    var outputs = strings(node.path("outputs"), name + ".outputs", PARAMETER_NAME);
    String messageParameter = node.hasNonNull("messageParameter")
        ? node.get("messageParameter").asText().trim().toUpperCase() : "RETURN";
    if (!PARAMETER_NAME.matcher(messageParameter).matches()) throw invalid(name + ".messageParameter is invalid");
    int maxRows = node.path("maxRows").asInt(50);
    if (maxRows < 1 || maxRows > 500) throw invalid(name + ".maxRows must be between 1 and 500");
    JsonNode approval = node.path("approval");
    String approvedBy = approval.path("approvedBy").asText("").trim();
    String approvedOn = approval.path("approvedOn").asText("").trim();
    String review = approval.path("reviewReference").asText("").trim();
    if (allowExecute) {
      if (outputs.isEmpty()) throw invalid(name + ".outputs must list the parameters returned to n8n");
      if (approvedBy.isEmpty() || review.isEmpty()) {
        throw invalid(name + ".approval needs approvedBy and reviewReference before execution is allowed");
      }
      try { LocalDate.parse(approvedOn); }
      catch (RuntimeException error) { throw invalid(name + ".approval.approvedOn must use YYYY-MM-DD"); }
    }
    return new FunctionPolicy(name, node.path("title").asText(name).trim(),
        node.path("description").asText("").trim(), kind, objectType, method, effect, transaction,
        allowMetadata, allowExecute, inputs, fixed, outputs, messageParameter, maxRows,
        strings(node.path("businessAuthorizations"), name + ".businessAuthorizations", null),
        approvedBy, approvedOn, review, strings(node.path("tags"), name + ".tags", null));
  }

  private static Map<String, InputRule> inputs(String function, JsonNode node) {
    if (node.isMissingNode() || node.isNull()) return Map.of();
    if (!node.isObject()) throw invalid(function + ".inputs must be an object");
    var rules = new LinkedHashMap<String, InputRule>();
    var names = node.fieldNames();
    while (names.hasNext()) {
      String parameter = names.next();
      String label = function + ".inputs." + parameter;
      if (!PARAMETER_NAME.matcher(parameter).matches()) throw invalid(label + " is not a parameter name");
      JsonNode rule = node.get(parameter);
      if (!rule.isObject()) throw invalid(label + " must be an object");
      allowedKeys(rule, label, Set.of("required", "pattern", "allowedValues", "pad", "maxRows", "fields"));
      Pattern pattern = null;
      if (rule.hasNonNull("pattern")) {
        String raw = rule.get("pattern").asText();
        if (raw.length() > 200) throw invalid(label + ".pattern is too long");
        try { pattern = Pattern.compile(raw); }
        catch (RuntimeException error) { throw invalid(label + ".pattern is not a valid regular expression"); }
      }
      String pad = rule.path("pad").asText("");
      if (!pad.isEmpty() && !"alpha".equals(pad)) throw invalid(label + ".pad must be alpha");
      int maxRows = rule.path("maxRows").asInt(20);
      if (maxRows < 1 || maxRows > 200) throw invalid(label + ".maxRows must be between 1 and 200");
      var fields = new LinkedHashSet<>(strings(rule.path("fields"), label + ".fields", PARAMETER_NAME));
      rules.put(parameter, new InputRule(rule.path("required").asBoolean(false), pattern,
          strings(rule.path("allowedValues"), label + ".allowedValues", null), "alpha".equals(pad),
          maxRows, Set.copyOf(fields)));
    }
    return Map.copyOf(rules);
  }

  private static Map<String, String> fixed(String function, JsonNode node) {
    if (node.isMissingNode() || node.isNull()) return Map.of();
    if (!node.isObject()) throw invalid(function + ".fixed must be an object");
    var values = new LinkedHashMap<String, String>();
    var names = node.fieldNames();
    while (names.hasNext()) {
      String parameter = names.next();
      if (!PARAMETER_NAME.matcher(parameter).matches() || !node.get(parameter).isTextual()) {
        throw invalid(function + ".fixed." + parameter + " must be a text value for a scalar parameter");
      }
      values.put(parameter, node.get(parameter).asText());
    }
    return Map.copyOf(values);
  }

  private static List<String> strings(JsonNode node, String label, Pattern pattern) {
    if (node.isMissingNode() || node.isNull()) return List.of();
    if (!node.isArray()) throw invalid(label + " must be an array");
    var values = new ArrayList<String>();
    for (JsonNode item : node) {
      if (!item.isTextual()) throw invalid(label + " must contain text values");
      String value = pattern == null ? item.asText() : item.asText().trim().toUpperCase();
      if (pattern != null && !pattern.matcher(value).matches()) throw invalid(label + " contains " + value);
      values.add(value);
    }
    return List.copyOf(values);
  }

  private static String text(JsonNode node, String key, String label) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid(label + "." + key + " is required");
    return value.asText().trim();
  }

  private static boolean bool(JsonNode node, String key, boolean fallback) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) return fallback;
    if (!value.isBoolean()) throw invalid(key + " must be true or false");
    return value.asBoolean();
  }

  private static void allowedKeys(JsonNode node, String label, Set<String> allowed) {
    var names = node.fieldNames();
    while (names.hasNext()) {
      String key = names.next();
      if (!allowed.contains(key)) throw invalid(label + " has the unknown key " + key);
    }
  }

  private static String sha256(byte[] content) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  static IllegalArgumentException invalid(String reason) {
    return new IllegalArgumentException("CATALOG_POLICY_INVALID: " + reason);
  }
}
