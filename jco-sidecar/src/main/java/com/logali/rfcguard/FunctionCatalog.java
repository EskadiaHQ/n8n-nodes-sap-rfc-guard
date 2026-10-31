package com.logali.rfcguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Dynamic catalog of approved SAP functions: the visible list is the intersection of the
 * server-side policy and what SAP really exposes; every call is re-checked against both.
 */
final class FunctionCatalog {
  static final String SIGNATURE_SOURCE = "JCo repository (RFC_METADATA_GET)";
  private static final int MAX_INPUT_TEXT = 4096;
  private final CatalogPolicySource policySource;
  private final CatalogBackend backend;
  private final int operatedMaxRows;
  private final int maxResponseBytes;
  private final BoundedCache cache;
  private final ObjectMapper mapper = new ObjectMapper();

  record Settings(int cacheTtlSeconds, int cacheMaxEntries, int maxResponseBytes) {
    static Settings fromEnvironment() {
      var env = System.getenv();
      return new Settings(
          integer(env.get("RFC_GUARD_CATALOG_CACHE_TTL_SECONDS"), 900, 30, 86_400),
          integer(env.get("RFC_GUARD_CATALOG_CACHE_MAX_ENTRIES"), 256, 16, 4096),
          integer(env.get("RFC_GUARD_CATALOG_MAX_RESPONSE_BYTES"), 1_048_576, 16_384, 8_388_608));
    }

    private static int integer(String raw, int fallback, int min, int max) {
      if (raw == null || raw.isBlank()) return fallback;
      int value = Integer.parseInt(raw.trim());
      if (value < min || value > max) throw new IllegalArgumentException("Catalog setting outside the permitted range");
      return value;
    }
  }

  FunctionCatalog(CatalogPolicySource policySource, CatalogBackend backend, int operatedMaxRows,
      Settings settings) {
    this.policySource = policySource;
    this.backend = backend;
    this.operatedMaxRows = operatedMaxRows;
    this.maxResponseBytes = settings.maxResponseBytes();
    this.cache = new BoundedCache(settings.cacheMaxEntries(), settings.cacheTtlSeconds() * 1000L);
  }

  /** Health summary; never calls SAP. */
  Map<String, Object> health() {
    var output = new LinkedHashMap<String, Object>();
    String failure = policySource.failure();
    output.put("enabled", failure == null);
    output.put("effects", List.of("read"));
    if (failure != null) {
      output.put("error", "CATALOG_POLICY_INVALID");
      return output;
    }
    var policy = policySource.current();
    output.put("policySha256", policy.sha256());
    output.put("approvedFunctions", policy.functions().size());
    output.put("metadataSearch", policy.metadataSearch());
    return output;
  }

  Map<String, Object> list(String query, int cursor, int limit) {
    var policy = policySource.current();
    if (limit < 1 || limit > 50) throw new CatalogException(400, "LIMIT_INVALID", "limit must be between 1 and 50.");
    if (cursor < 0) throw new CatalogException(400, "CURSOR_INVALID", "cursor must be zero or positive.");
    String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > 60) throw new CatalogException(400, "QUERY_INVALID", "query must contain at most 60 characters.");
    var candidates = policy.functions().values().stream()
        .filter(function -> function.allowMetadata() || function.allowExecute())
        .filter(function -> normalized.isEmpty() || matches(function, normalized))
        .sorted(Comparator.comparing(CatalogPolicy.FunctionPolicy::name)).toList();
    String prefix = systemKey();
    var visible = new ArrayList<Map<String, Object>>();
    var excluded = new ArrayList<Map<String, Object>>();
    for (var function : candidates) {
      var remote = remote(prefix, function.name());
      if (remote.isEmpty()) excluded.add(Map.of("name", function.name(), "reason", "NOT_FOUND_IN_SAP"));
      else if (!remote.get().remoteEnabled()) excluded.add(Map.of("name", function.name(), "reason", "NOT_REMOTE_ENABLED"));
      else visible.add(summary(function));
    }
    int end = Math.min(visible.size(), cursor + limit);
    var page = cursor >= visible.size() ? List.<Map<String, Object>>of() : visible.subList(cursor, end);
    var output = new LinkedHashMap<String, Object>();
    output.put("functions", page);
    output.put("page", pageMap(cursor, limit, visible.size(), end < visible.size() ? end : null));
    output.put("excluded", excluded);
    output.put("discovery", Map.of("approvedSource", "sidecar policy",
        "sapSource", "RFC_GET_FUNCTION_INTERFACE", "rule", "approved AND present AND remote-enabled"));
    output.put("policySha256", policy.sha256());
    return output;
  }

  /** Administrative search in SAP for candidates to review; results are never executable by themselves. */
  Map<String, Object> search(String pattern, int limit) {
    var policy = policySource.current();
    if (!policy.metadataSearch()) {
      throw new CatalogException(403, "METADATA_SEARCH_NOT_APPROVED",
          "The catalog policy does not allow searching SAP for new functions (metadataSearch=false).");
    }
    String normalized = pattern == null ? "" : pattern.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z0-9_/*]{2,30}") || normalized.replace("*", "").length() < 3) {
      throw new CatalogException(400, "SEARCH_PATTERN_INVALID",
          "pattern must contain 3-30 letters, numbers, underscores or slashes, optionally with *.");
    }
    if (limit < 1 || limit > 50) throw new CatalogException(400, "LIMIT_INVALID", "limit must be between 1 and 50.");
    List<CatalogBackend.SearchHit> hits;
    try { hits = backend.search(normalized, limit + 1); }
    catch (CatalogBackend.SapCallException error) { throw sapFailure(error, "RFC_FUNCTION_SEARCH"); }
    var rows = new ArrayList<Map<String, Object>>();
    for (var hit : hits.subList(0, Math.min(limit, hits.size()))) {
      var row = new LinkedHashMap<String, Object>();
      row.put("name", hit.name());
      row.put("group", hit.group());
      row.put("shortText", hit.shortText());
      row.put("approved", policy.functions().containsKey(hit.name()));
      row.put("catalogable", !CatalogPolicy.forbidden(hit.name()));
      rows.add(row);
    }
    return Map.of("candidates", rows, "truncated", hits.size() > limit,
        "note", "Candidates are informational. Add a reviewed policy entry before any use.");
  }

  Map<String, Object> signature(String rawName) {
    var policy = policySource.current();
    var function = approved(policy, rawName);
    if (!function.allowMetadata()) {
      throw new CatalogException(403, "METADATA_NOT_APPROVED",
          function.name() + " is approved, but its signature is not approved for consultation (allowMetadata=false).");
    }
    String prefix = systemKey();
    var remote = requireRemote(prefix, function.name());
    var signature = requireSignature(prefix, function.name());
    var output = new LinkedHashMap<String, Object>();
    output.putAll(summary(function));
    output.put("remote", Map.of("remoteEnabled", remote.remoteEnabled(), "updateTask", remote.updateTask()));
    output.put("parameters", signature.parameters().stream().map(this::parameterMap).toList());
    output.put("exceptions", signature.exceptions());
    output.put("policy", policyView(function, signature));
    output.put("bapi", bapiView(prefix, function));
    output.put("documentation", documentation(prefix, function));
    output.put("permissions", Map.of(
        "metadata", "approved by the catalog policy",
        "execute", function.allowExecute() ? "approved by the catalog policy; SAP still checks S_RFC on every call"
            : "not approved",
        "business", function.businessAuthorizations()));
    output.put("signatureSource", SIGNATURE_SOURCE);
    output.put("policySha256", policy.sha256());
    output.put("backend", backendMap(backend.backend()));
    return output;
  }

  /** Validates and, unless dryRun, executes one approved read. The policy is re-read here. */
  Map<String, Object> execute(String rawName, Map<String, Object> rawParameters, Integer requestedRows,
      boolean dryRun) {
    long started = System.nanoTime();
    var policy = policySource.current();
    var function = approved(policy, rawName);
    if (!function.allowExecute()) {
      throw new CatalogException(403, "EXECUTION_NOT_APPROVED",
          function.name() + " is in the catalog but not approved for execution (allowExecute=false).");
    }
    if (!"read".equals(function.effect())) {
      throw new CatalogException(403, "EFFECT_NOT_SUPPORTED", "Only approved reads can be executed.");
    }
    int rowLimit = Math.min(function.maxRows(), operatedMaxRows);
    if (requestedRows != null) {
      if (requestedRows < 1) throw new CatalogException(400, "MAX_ROWS_INVALID", "maxRows must be at least 1.");
      rowLimit = Math.min(requestedRows, rowLimit);
    }
    String prefix = systemKey();
    var remote = requireRemote(prefix, function.name());
    if (remote.updateTask()) {
      throw new CatalogException(403, "FUNCTION_NOT_REMOTE", function.name() + " is an update-task module and cannot be called.");
    }
    var signature = requireSignature(prefix, function.name());
    var bapi = bapiView(prefix, function);
    if (Boolean.FALSE.equals(bapi.get("verified"))) {
      throw new CatalogException(409, "POLICY_KIND_MISMATCH", String.valueOf(bapi.get("reason")),
          Map.of("function", function.name()));
    }
    var request = prepare(function, signature, rawParameters == null ? Map.of() : rawParameters, rowLimit);
    var output = new LinkedHashMap<String, Object>();
    output.put("function", function.name());
    output.put("title", function.title());
    if (dryRun) {
      output.put("outcome", "VALIDATED");
      output.put("validatedParameters", echo(request));
      output.put("meta", meta(policy, function, bapi, "not-called", rowLimit, Map.of(), started, true));
      return output;
    }
    CatalogBackend.CallResult result;
    try { result = backend.call(request); }
    catch (CatalogBackend.SapCallException error) { throw sapFailure(error, function.name()); }
    var messages = messages(result, function.messageParameter());
    output.put("outcome", outcome(messages));
    output.put("messages", messages);
    output.put("exports", project(result.exports(), function));
    output.put("changing", project(result.changing(), function));
    output.put("tables", project(result.tables(), function));
    output.put("meta", meta(policy, function, bapi, result.transaction(), rowLimit, result.tableRows(),
        started, false));
    enforceSize(output);
    return output;
  }

  // ---- Policy and SAP checks ---------------------------------------------------------------

  private CatalogPolicy.FunctionPolicy approved(CatalogPolicy policy, String rawName) {
    String name = rawName == null ? "" : rawName.trim().toUpperCase(Locale.ROOT);
    if (!CatalogPolicy.FUNCTION_NAME.matcher(name).matches()) {
      throw new CatalogException(400, "FUNCTION_NAME_INVALID", "The function module name is invalid.");
    }
    if (CatalogPolicy.forbidden(name)) {
      throw new CatalogException(403, "FUNCTION_FORBIDDEN",
          name + " is a generic executor or has a dedicated governed flow and can never be called from the catalog.");
    }
    var function = policy.functions().get(name);
    if (function == null) {
      throw new CatalogException(403, "FUNCTION_NOT_APPROVED",
          name + " is not in the approved catalog. Ask the SAP owner to review and approve it first.");
    }
    return function;
  }

  private CatalogBackend.RemoteInfo requireRemote(String prefix, String name) {
    var remote = remote(prefix, name);
    if (remote.isEmpty()) {
      throw new CatalogException(404, "FUNCTION_NOT_FOUND_IN_SAP", name + " is approved but does not exist in this SAP system.");
    }
    if (!remote.get().remoteEnabled()) {
      throw new CatalogException(403, "FUNCTION_NOT_REMOTE", name + " exists in SAP but is not remote-enabled.");
    }
    return remote.get();
  }

  private CatalogBackend.Signature requireSignature(String prefix, String name) {
    var signature = cache.get(prefix + "|signature|" + name, () -> {
      try { return Optional.ofNullable(backend.signature(name)); }
      catch (CatalogBackend.SapCallException error) { throw sapFailure(error, "RFC_METADATA_GET"); }
    });
    if (signature.isEmpty()) {
      throw new CatalogException(404, "FUNCTION_NOT_FOUND_IN_SAP", name + " has no metadata in this SAP system.");
    }
    return signature.get();
  }

  private Optional<CatalogBackend.RemoteInfo> remote(String prefix, String name) {
    return cache.get(prefix + "|remote|" + name, () -> {
      try { return Optional.ofNullable(backend.remoteInfo(name)); }
      catch (CatalogBackend.SapCallException error) { throw sapFailure(error, "RFC_GET_FUNCTION_INTERFACE"); }
    });
  }

  /** BOR registration of a declared BAPI; verified only when SAP allows SWO_QUERY_API_METHODS. */
  private Map<String, Object> bapiView(String prefix, CatalogPolicy.FunctionPolicy function) {
    var view = new LinkedHashMap<String, Object>();
    view.put("declaredKind", function.kind().name());
    var lookup = cache.get(prefix + "|bor", () -> {
      try { return new Lookup<>(backend.borMethods(), null); }
      catch (CatalogBackend.SapCallException error) { return new Lookup<Map<String, List<CatalogBackend.BorMethod>>>(null, error.code()); }
    });
    if (lookup.value() == null) {
      view.put("verified", null);
      view.put("reason", "BOR registration not checked: " + lookup.unavailable()
          + " for SWO_QUERY_API_METHODS. The kind is taken from the reviewed policy.");
      if (function.kind() == CatalogPolicy.Kind.BAPI) {
        view.put("objectType", function.borObjectType());
        view.put("method", function.borMethod());
      }
      return view;
    }
    var methods = lookup.value().getOrDefault(function.name(), List.of());
    view.put("registeredMethods", methods.stream().map(method -> Map.of("objectType", method.objectType(),
        "method", method.method(), "objectName", method.objectName(), "released", method.released())).toList());
    if (function.kind() == CatalogPolicy.Kind.RFC) {
      view.put("verified", true);
      view.put("reason", methods.isEmpty() ? "Generic remote function, not registered in the BOR."
          : "Declared as RFC; SAP also registers it as a BOR method.");
      return view;
    }
    view.put("objectType", function.borObjectType());
    view.put("method", function.borMethod());
    var declared = methods.stream().filter(method -> method.objectType().equals(function.borObjectType())
        && method.method().equals(function.borMethod())).findFirst();
    view.put("verified", declared.isPresent());
    view.put("released", declared.map(CatalogBackend.BorMethod::released).orElse(false));
    view.put("reason", declared.isPresent()
        ? "BOR method " + function.borObjectType() + "." + function.borMethod() + " maps to " + function.name() + "."
        : "The policy declares " + function.name() + " as BAPI " + function.borObjectType() + "."
            + function.borMethod() + ", but SAP does not register that BOR method for it.");
    return view;
  }

  private Map<String, Object> documentation(String prefix, CatalogPolicy.FunctionPolicy function) {
    if (function.kind() != CatalogPolicy.Kind.BAPI) {
      return Map.of("available", false, "reason",
          "SAP documentation is read with BAPI_INTERFACE_GETDOCU for BOR BAPIs only; parameter texts come from the signature.");
    }
    var lookup = cache.get(prefix + "|docu|" + function.name(), () -> {
      try { return new Lookup<>(backend.bapiDocumentation(function.borObjectType(), function.borMethod(), 200), null); }
      catch (CatalogBackend.SapCallException error) { return new Lookup<List<String>>(null, error.code()); }
    });
    if (lookup.value() == null) {
      return Map.of("available", false, "reason", lookup.unavailable() + " for BAPI_INTERFACE_GETDOCU");
    }
    return Map.of("available", true, "source", "BAPI_INTERFACE_GETDOCU", "lines", lookup.value());
  }

  private CatalogException sapFailure(CatalogBackend.SapCallException error, String function) {
    var details = new LinkedHashMap<String, Object>();
    details.put("category", error.category());
    details.put("function", function);
    if (!error.sapKey().isBlank()) details.put("sapKey", error.sapKey());
    if (!error.getMessage().isBlank()) details.put("sapMessage", error.getMessage());
    return switch (error.code()) {
      case "SAP_RFC_NOT_AUTHORIZED" -> new CatalogException(403, error.code(),
          "SAP denied the RFC call to " + function + " for the sidecar's technical user (S_RFC). The catalog does not extend SAP roles.", details);
      case "ABAP_EXCEPTION" -> new CatalogException(422, error.code(),
          function + " raised the ABAP exception " + error.sapKey() + ".", details);
      case "SAP_ABAP_RUNTIME_ERROR" -> new CatalogException(502, error.code(),
          function + " failed inside SAP with a runtime error.", details);
      case "SAP_VALUE_REJECTED" -> new CatalogException(400, error.code(),
          "SAP JCo rejected a parameter value for " + function + ".", details);
      case "SAP_FUNCTION_NOT_FOUND" -> new CatalogException(404, "FUNCTION_NOT_FOUND_IN_SAP",
          function + " does not exist in this SAP system.", details);
      case "SAP_LOGON_FAILED" -> new CatalogException(502, error.code(),
          "The sidecar could not log on to SAP.", Map.of("category", "TRANSPORT"));
      default -> new CatalogException(502, "SAP_TRANSPORT_FAILURE",
          "The sidecar could not reach SAP for " + function + ".", Map.of("category", "TRANSPORT", "function", function));
    };
  }

  // ---- Input validation against the real signature ----------------------------------------

  private CatalogBackend.CallRequest prepare(CatalogPolicy.FunctionPolicy function,
      CatalogBackend.Signature signature, Map<String, Object> raw, int rowLimit) {
    var imports = new LinkedHashMap<String, Object>();
    var changing = new LinkedHashMap<String, Object>();
    var tables = new LinkedHashMap<String, Object>();
    var supplied = new LinkedHashMap<String, Object>();
    for (var entry : raw.entrySet()) {
      String name = entry.getKey().trim().toUpperCase(Locale.ROOT);
      if (supplied.put(name, entry.getValue()) != null) {
        throw new CatalogException(400, "PARAMETER_DUPLICATED", name + " is supplied twice.");
      }
    }
    for (var entry : supplied.entrySet()) {
      String name = entry.getKey();
      var parameter = signature.parameter(name);
      if (parameter == null) {
        throw new CatalogException(400, "PARAMETER_UNKNOWN", function.name() + " has no parameter " + name
            + "." + suggestion(name, signature.parameters().stream().map(CatalogBackend.Parameter::name).toList()),
            Map.of("parameter", name));
      }
      if ("EXPORT".equals(parameter.direction())) {
        throw new CatalogException(400, "PARAMETER_NOT_INPUT", name + " is an EXPORT parameter and is returned by SAP, not sent.",
            Map.of("parameter", name));
      }
      if (function.fixed().containsKey(name)) {
        throw new CatalogException(400, "PARAMETER_FIXED_BY_POLICY",
            name + " is fixed by the catalog policy and cannot be supplied.", Map.of("parameter", name));
      }
      var rule = function.inputs().get(name);
      if (rule == null) {
        throw new CatalogException(400, "PARAMETER_NOT_ALLOWED", name + " exists in SAP but is not approved as an input for "
            + function.name() + ". Approved inputs: " + String.join(", ", function.inputs().keySet()) + ".",
            Map.of("parameter", name));
      }
      Object value = convert(name, parameter.type(), parameter.length(), parameter.decimals(),
          parameter.fields(), entry.getValue(), rule, 1);
      if (value != null) target(parameter, imports, changing, tables).put(name, value);
    }
    for (var fixed : function.fixed().entrySet()) {
      var parameter = signature.parameter(fixed.getKey());
      if (parameter == null || !"IMPORT".equals(parameter.direction()) || complex(parameter.type())) {
        throw new CatalogException(409, "POLICY_INCOMPLETE",
            "The policy fixes " + fixed.getKey() + ", which is not a scalar IMPORT parameter of " + function.name() + ".");
      }
      Object value = convert(fixed.getKey(), parameter.type(), parameter.length(), parameter.decimals(),
          List.of(), fixed.getValue(), null, 1);
      if (value != null) imports.put(fixed.getKey(), value);
    }
    for (var parameter : signature.parameters()) {
      String name = parameter.name();
      var rule = function.inputs().get(name);
      boolean mandatory = "IMPORT".equals(parameter.direction()) && !parameter.optional();
      if (supplied.containsKey(name) || function.fixed().containsKey(name)) continue;
      if (mandatory && rule == null) {
        throw new CatalogException(409, "POLICY_INCOMPLETE", "SAP requires " + name + " for " + function.name()
            + ", but the catalog policy neither approves it as an input nor fixes it.");
      }
      if (mandatory || (rule != null && rule.required())) {
        throw new CatalogException(400, "PARAMETER_REQUIRED", name + " is required for " + function.name() + " ("
            + describeType(parameter.type(), parameter.length(), parameter.decimals()) + ").",
            Map.of("parameter", name));
      }
    }
    for (String rule : function.inputs().keySet()) {
      if (signature.parameter(rule) == null) {
        throw new CatalogException(409, "POLICY_INCOMPLETE",
            "The policy approves the input " + rule + ", which SAP does not expose for " + function.name() + ".");
      }
    }
    var outputs = new ArrayList<>(function.outputs());
    if (!outputs.contains(function.messageParameter())) outputs.add(function.messageParameter());
    return new CatalogBackend.CallRequest(function.name(), Map.copyOf(imports), Map.copyOf(changing),
        Map.copyOf(tables), List.copyOf(outputs), rowLimit, function.rollbackAfterCall());
  }

  private static Map<String, Object> target(CatalogBackend.Parameter parameter, Map<String, Object> imports,
      Map<String, Object> changing, Map<String, Object> tables) {
    return switch (parameter.direction()) {
      case "CHANGING" -> changing;
      case "TABLES" -> tables;
      default -> imports;
    };
  }

  @SuppressWarnings("unchecked")
  private Object convert(String path, String type, int length, int decimals, List<CatalogBackend.Field> fields,
      Object raw, CatalogPolicy.InputRule rule, int depth) {
    if (raw == null) {
      throw new CatalogException(400, "VALUE_REQUIRED", path + " is null; omit it or send a value.", Map.of("field", path));
    }
    return switch (type) {
      case "CHAR", "STRING", "SSTRING", "UTCLONG" -> {
        String text = text(path, type, length, raw);
        if (rule != null && rule.alphaPad() && text.matches("[0-9]+") && text.length() < length) {
          text = "0".repeat(length - text.length()) + text;
        }
        int limit = "CHAR".equals(type) ? length : MAX_INPUT_TEXT;
        if (text.length() > limit) {
          throw new CatalogException(400, "VALUE_TOO_LONG", path + " accepts at most " + limit + " characters.", Map.of("field", path));
        }
        yield checkRule(path, text, rule);
      }
      case "NUM" -> {
        String text = text(path, type, length, raw);
        if (!text.matches("[0-9]*") || text.length() > length) {
          throw new CatalogException(400, "TYPE_MISMATCH", path + " is NUMC(" + length + ") and accepts only up to "
              + length + " digits, sent as text.", Map.of("field", path));
        }
        yield checkRule(path, "0".repeat(length - text.length()) + text, rule);
      }
      case "BCD", "DECF16", "DECF34" -> {
        BigDecimal value = decimal(path, raw);
        BigDecimal normalized = value.stripTrailingZeros();
        int scale = Math.max(0, normalized.scale());
        int digits = "BCD".equals(type) ? 2 * length - 1 : "DECF16".equals(type) ? 16 : 34;
        if ("BCD".equals(type) && scale > decimals) {
          throw new CatalogException(400, "DECIMAL_SCALE_EXCEEDED", path + " accepts " + decimals
              + " decimal places; the value would have to be rounded.", Map.of("field", path));
        }
        if (normalized.precision() - normalized.scale() > digits - ("BCD".equals(type) ? decimals : 0)) {
          throw new CatalogException(400, "DECIMAL_TOO_LARGE", path + " does not fit " + describeType(type, length, decimals) + ".",
              Map.of("field", path));
        }
        if (rule != null) checkRule(path, value.toPlainString(), rule);
        yield value;
      }
      case "FLOAT" -> {
        BigDecimal value = decimal(path, raw);
        yield value.doubleValue();
      }
      case "INT", "INT1", "INT2", "INT8" -> {
        BigInteger value;
        try {
          value = raw instanceof BigInteger integer ? integer
              : raw instanceof Number number ? new BigDecimal(number.toString()).toBigIntegerExact()
              : new BigInteger(raw.toString().trim());
        } catch (RuntimeException error) {
          throw new CatalogException(400, "TYPE_MISMATCH", path + " is " + type + " and accepts whole numbers only.", Map.of("field", path));
        }
        long min = switch (type) { case "INT1" -> 0; case "INT2" -> Short.MIN_VALUE; case "INT" -> Integer.MIN_VALUE; default -> Long.MIN_VALUE; };
        long max = switch (type) { case "INT1" -> 255; case "INT2" -> Short.MAX_VALUE; case "INT" -> Integer.MAX_VALUE; default -> Long.MAX_VALUE; };
        if (value.compareTo(BigInteger.valueOf(min)) < 0 || value.compareTo(BigInteger.valueOf(max)) > 0) {
          throw new CatalogException(400, "VALUE_OUT_OF_RANGE", path + " must be between " + min + " and " + max + ".", Map.of("field", path));
        }
        yield "INT8".equals(type) ? (Object) value.longValueExact() : (Object) value.intValueExact();
      }
      case "DATE" -> {
        String text = text(path, type, length, raw);
        if (text.isEmpty()) yield null;
        try { yield LocalDate.parse(text).toString().replace("-", ""); }
        catch (RuntimeException error) {
          throw new CatalogException(400, "TYPE_MISMATCH", path + " is a DATE; use YYYY-MM-DD.", Map.of("field", path));
        }
      }
      case "TIME" -> {
        String text = text(path, type, length, raw);
        if (text.isEmpty()) yield null;
        try { yield LocalTime.parse(text).toString().replace(":", "").substring(0, 6); }
        catch (RuntimeException error) {
          throw new CatalogException(400, "TYPE_MISMATCH", path + " is a TIME; use HH:MM:SS.", Map.of("field", path));
        }
      }
      case "STRUCTURE" -> {
        if (!(raw instanceof Map<?, ?> map)) {
          throw new CatalogException(400, "TYPE_MISMATCH", path + " is a structure; send a JSON object with its fields.", Map.of("field", path));
        }
        yield record(path, (Map<String, Object>) map, fields, rule, depth);
      }
      case "TABLE" -> {
        if (!(raw instanceof List<?> list)) {
          throw new CatalogException(400, "TYPE_MISMATCH", path + " is a table; send a JSON array of row objects.", Map.of("field", path));
        }
        int maxRows = rule == null ? 20 : rule.maxRows();
        if (list.size() > maxRows) {
          throw new CatalogException(400, "TOO_MANY_ROWS", path + " accepts at most " + maxRows + " input rows.", Map.of("field", path));
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < list.size(); index++) {
          if (!(list.get(index) instanceof Map<?, ?> row)) {
            throw new CatalogException(400, "TYPE_MISMATCH", path + "[" + index + "] must be a JSON object.", Map.of("field", path));
          }
          rows.add(record(path + "[" + index + "]", (Map<String, Object>) row, fields, rule, depth));
        }
        yield List.copyOf(rows);
      }
      default -> throw new CatalogException(400, "TYPE_NOT_SUPPORTED",
          path + " has the SAP type " + type + ", which the catalog does not accept as input.", Map.of("field", path));
    };
  }

  private Map<String, Object> record(String path, Map<String, Object> raw, List<CatalogBackend.Field> fields,
      CatalogPolicy.InputRule rule, int depth) {
    if (depth > 4) throw new CatalogException(400, "NESTING_TOO_DEEP", path + " is nested too deeply.");
    var output = new LinkedHashMap<String, Object>();
    for (var entry : raw.entrySet()) {
      String name = entry.getKey().trim().toUpperCase(Locale.ROOT);
      var field = fields.stream().filter(item -> item.name().equals(name)).findFirst().orElse(null);
      String fieldPath = path + "." + name;
      if (field == null) {
        throw new CatalogException(400, "FIELD_UNKNOWN", fieldPath + " is not a field of " + path + "."
            + suggestion(name, fields.stream().map(CatalogBackend.Field::name).toList()), Map.of("field", fieldPath));
      }
      if (depth == 1 && rule != null && !rule.fields().isEmpty() && !rule.fields().contains(name)) {
        throw new CatalogException(400, "FIELD_NOT_ALLOWED", fieldPath + " is not approved by the catalog policy.",
            Map.of("field", fieldPath));
      }
      Object value = convert(fieldPath, field.type(), field.length(), field.decimals(), field.fields(),
          entry.getValue(), null, depth + 1);
      if (value != null) output.put(name, value);
    }
    return output;
  }

  private static String text(String path, String type, int length, Object raw) {
    if (raw instanceof String text) return text;
    String example = "NUM".equals(type) || "CHAR".equals(type) ? " e.g. \"" + "0".repeat(Math.max(0, Math.min(length, 10) - 1)) + "1\"" : "";
    throw new CatalogException(400, "TYPE_MISMATCH", path + " is " + describeType(type, length, 0)
        + "; send it as a JSON string" + example + " so leading zeros and exact values are kept.", Map.of("field", path));
  }

  private static BigDecimal decimal(String path, Object raw) {
    try {
      if (raw instanceof BigDecimal decimal) return decimal;
      if (raw instanceof BigInteger integer) return new BigDecimal(integer);
      if (raw instanceof String text && text.trim().matches("-?[0-9]+(\\.[0-9]+)?")) return new BigDecimal(text.trim());
    } catch (RuntimeException ignored) { /* Reported below. */ }
    throw new CatalogException(400, "TYPE_MISMATCH", path + " is a decimal; send it as text such as \"12.50\".", Map.of("field", path));
  }

  private static String checkRule(String path, String value, CatalogPolicy.InputRule rule) {
    if (rule == null) return value;
    boolean initial = value.isEmpty() || value.chars().allMatch(character -> character == '0');
    if (rule.pattern() != null && !(initial && !rule.required()) && !rule.pattern().matcher(value).matches()) {
      throw new CatalogException(400, "VALUE_NOT_ALLOWED", path + " does not match the format approved by the catalog policy.",
          Map.of("field", path));
    }
    if (!rule.allowedValues().isEmpty() && !rule.allowedValues().contains(value)) {
      throw new CatalogException(400, "VALUE_NOT_ALLOWED", path + " must be one of " + rule.allowedValues() + ".",
          Map.of("field", path, "allowedValues", rule.allowedValues()));
    }
    return value;
  }

  // ---- Output -----------------------------------------------------------------------------

  private static Map<String, Object> project(Map<String, Object> values, CatalogPolicy.FunctionPolicy function) {
    var output = new LinkedHashMap<String, Object>();
    values.forEach((name, value) -> { if (function.outputs().contains(name)) output.put(name, value); });
    return output;
  }

  private static List<Map<String, Object>> messages(CatalogBackend.CallResult result, String parameter) {
    Object raw = result.exports().containsKey(parameter) ? result.exports().get(parameter)
        : result.tables().get(parameter);
    var rows = new ArrayList<Map<String, Object>>();
    if (raw instanceof Map<?, ?> single) addMessage(rows, single);
    else if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?, ?> row) addMessage(rows, row);
    return rows;
  }

  private static void addMessage(List<Map<String, Object>> rows, Map<?, ?> row) {
    String type = String.valueOf(row.containsKey("TYPE") ? row.get("TYPE") : "").trim();
    String message = String.valueOf(row.containsKey("MESSAGE") ? row.get("MESSAGE") : "").trim();
    if (type.isEmpty() && message.isEmpty()) return;
    var output = new LinkedHashMap<String, Object>();
    output.put("type", type);
    for (String key : List.of("ID", "NUMBER", "CODE", "PARAMETER", "FIELD")) {
      Object value = row.get(key);
      if (value != null && !value.toString().isBlank()) output.put(key.toLowerCase(Locale.ROOT), value.toString().trim());
    }
    output.put("message", message);
    rows.add(output);
  }

  private static String outcome(List<Map<String, Object>> messages) {
    var types = messages.stream().map(message -> String.valueOf(message.get("type"))).toList();
    if (types.stream().anyMatch(type -> Set.of("E", "A", "X").contains(type))) return "BUSINESS_ERROR";
    if (types.contains("W")) return "WARNING";
    return "SUCCESS";
  }

  private Map<String, Object> meta(CatalogPolicy policy, CatalogPolicy.FunctionPolicy function,
      Map<String, Object> bapi, String transaction, int rowLimit, Map<String, Integer> tableRows, long started,
      boolean dryRun) {
    var meta = new LinkedHashMap<String, Object>();
    meta.put("source", "sap-jco");
    meta.put("syntheticData", false);
    meta.put("readOnly", true);
    meta.put("write", false);
    meta.put("dryRun", dryRun);
    meta.put("function", function.name());
    meta.put("kind", function.kind().name());
    meta.put("kindVerified", bapi.get("verified"));
    meta.put("effect", function.effect());
    meta.put("transactionPolicy", function.transaction());
    meta.put("transaction", transaction);
    meta.put("rowLimit", rowLimit);
    var truncated = new LinkedHashMap<String, Object>();
    tableRows.forEach((name, rows) -> { if (rows > rowLimit) truncated.put(name, rows); });
    meta.put("tableRows", tableRows);
    meta.put("truncated", truncated);
    meta.put("policySha256", policy.sha256());
    meta.put("approval", Map.of("approvedBy", function.approvedBy(), "approvedOn", function.approvedOn(),
        "reviewReference", function.reviewReference()));
    meta.put("signatureSource", SIGNATURE_SOURCE);
    meta.put("backend", backendMap(backend.backend()));
    meta.put("executedAt", Instant.now().toString());
    meta.put("durationMs", (System.nanoTime() - started) / 1_000_000);
    return meta;
  }

  private void enforceSize(Map<String, Object> output) {
    try {
      if (mapper.writeValueAsBytes(output).length > maxResponseBytes) {
        throw new CatalogException(413, "RESPONSE_TOO_LARGE", "The SAP result exceeds " + maxResponseBytes
            + " bytes. Lower maxRows or approve fewer outputs.");
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Map<String, Object> echo(CatalogBackend.CallRequest request) {
    var output = new LinkedHashMap<String, Object>();
    request.imports().forEach(output::put);
    request.changing().forEach(output::put);
    request.tables().forEach(output::put);
    output.replaceAll((ignored, value) -> value instanceof BigDecimal decimal ? decimal.toPlainString() : value);
    return output;
  }

  private Map<String, Object> summary(CatalogPolicy.FunctionPolicy function) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", function.name());
    output.put("title", function.title());
    output.put("description", function.description());
    output.put("kind", function.kind().name());
    if (function.kind() == CatalogPolicy.Kind.BAPI) {
      output.put("bor", Map.of("objectType", function.borObjectType(), "method", function.borMethod()));
    }
    output.put("effect", function.effect());
    output.put("transaction", function.transaction());
    output.put("allowMetadata", function.allowMetadata());
    output.put("allowExecute", function.allowExecute());
    output.put("tags", function.tags());
    return output;
  }

  private Map<String, Object> parameterMap(CatalogBackend.Parameter parameter) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", parameter.name());
    output.put("direction", parameter.direction());
    output.put("type", parameter.type());
    output.put("sapType", describeType(parameter.type(), parameter.length(), parameter.decimals()));
    output.put("required", "IMPORT".equals(parameter.direction()) && !parameter.optional());
    output.put("optional", parameter.optional());
    if (!parameter.defaultValue().isBlank()) output.put("default", parameter.defaultValue());
    output.put("description", parameter.description());
    if (!parameter.recordType().isBlank()) output.put("dictionaryType", parameter.recordType());
    if (!parameter.fields().isEmpty()) output.put("fields", parameter.fields().stream().map(this::fieldMap).toList());
    return output;
  }

  private Map<String, Object> fieldMap(CatalogBackend.Field field) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", field.name());
    output.put("type", field.type());
    output.put("sapType", describeType(field.type(), field.length(), field.decimals()));
    output.put("description", field.description());
    if (!field.recordType().isBlank()) output.put("dictionaryType", field.recordType());
    if (!field.fields().isEmpty()) output.put("fields", field.fields().stream().map(this::fieldMap).toList());
    return output;
  }

  private Map<String, Object> policyView(CatalogPolicy.FunctionPolicy function, CatalogBackend.Signature signature) {
    var inputs = new LinkedHashMap<String, Object>();
    function.inputs().forEach((name, rule) -> {
      var view = new LinkedHashMap<String, Object>();
      view.put("required", rule.required());
      if (rule.pattern() != null) view.put("pattern", rule.pattern().pattern());
      if (!rule.allowedValues().isEmpty()) view.put("allowedValues", rule.allowedValues());
      if (rule.alphaPad()) view.put("pad", "alpha");
      if (!rule.fields().isEmpty()) view.put("fields", rule.fields());
      inputs.put(name, view);
    });
    var warnings = new ArrayList<String>();
    for (var parameter : signature.parameters()) {
      if ("IMPORT".equals(parameter.direction()) && !parameter.optional()
          && !function.inputs().containsKey(parameter.name()) && !function.fixed().containsKey(parameter.name())) {
        warnings.add("SAP requires " + parameter.name() + " but the policy does not approve it.");
      }
    }
    for (String name : new LinkedHashSet<>(function.inputs().keySet())) {
      if (signature.parameter(name) == null) warnings.add("Approved input " + name + " does not exist in SAP.");
    }
    for (String name : function.outputs()) {
      if (signature.parameter(name) == null) warnings.add("Approved output " + name + " does not exist in SAP.");
    }
    var view = new LinkedHashMap<String, Object>();
    view.put("inputs", inputs);
    view.put("fixed", function.fixed());
    view.put("outputs", function.outputs());
    view.put("messageParameter", function.messageParameter());
    view.put("maxRows", Math.min(function.maxRows(), operatedMaxRows));
    view.put("approval", Map.of("approvedBy", function.approvedBy(), "approvedOn", function.approvedOn(),
        "reviewReference", function.reviewReference()));
    view.put("warnings", warnings);
    view.put("inputTemplate", template(function, signature));
    return view;
  }

  private static Map<String, Object> template(CatalogPolicy.FunctionPolicy function, CatalogBackend.Signature signature) {
    var template = new LinkedHashMap<String, Object>();
    function.inputs().keySet().stream().sorted().forEach(name -> {
      var parameter = signature.parameter(name);
      if (parameter == null) return;
      template.put(name, switch (parameter.type()) {
        case "STRUCTURE" -> Map.of();
        case "TABLE" -> List.of();
        default -> "";
      });
    });
    return template;
  }

  private static boolean matches(CatalogPolicy.FunctionPolicy function, String query) {
    return function.name().toLowerCase(Locale.ROOT).contains(query)
        || function.title().toLowerCase(Locale.ROOT).contains(query)
        || function.description().toLowerCase(Locale.ROOT).contains(query)
        || function.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(query));
  }

  private static Map<String, Object> pageMap(int cursor, int limit, int total, Integer next) {
    var page = new LinkedHashMap<String, Object>();
    page.put("cursor", cursor);
    page.put("limit", limit);
    page.put("total", total);
    page.put("nextCursor", next);
    return page;
  }

  private String systemKey() {
    var identity = backend.backend();
    return identity.systemId() + "/" + identity.client();
  }

  static Map<String, String> backendMap(SapAdapter.Backend backend) {
    return Map.of("systemId", backend.systemId(), "client", backend.client(), "host", backend.host(),
        "release", backend.release());
  }

  static String describeType(String type, int length, int decimals) {
    return switch (type) {
      case "CHAR" -> "CHAR(" + length + ")";
      case "NUM" -> "NUMC(" + length + ")";
      case "BCD" -> "DEC(" + (2 * length - 1) + "," + decimals + ")";
      default -> type;
    };
  }

  private static boolean complex(String type) { return "STRUCTURE".equals(type) || "TABLE".equals(type); }

  private static String suggestion(String name, List<String> candidates) {
    var close = candidates.stream().filter(candidate -> distance(candidate, name) <= 3
        || candidate.contains(name) || name.contains(candidate)).limit(3).toList();
    return close.isEmpty() ? "" : " Did you mean " + String.join(" or ", close) + "?";
  }

  private static int distance(String first, String second) {
    int[] previous = new int[second.length() + 1];
    for (int j = 0; j <= second.length(); j++) previous[j] = j;
    for (int i = 1; i <= first.length(); i++) {
      int[] current = new int[second.length() + 1];
      current[0] = i;
      for (int j = 1; j <= second.length(); j++) {
        int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      previous = current;
    }
    return previous[second.length()];
  }

  private record Lookup<T>(T value, String unavailable) {}

  /** LRU cache with expiry, keyed by system/client and function. Loads run outside the lock. */
  static final class BoundedCache {
    private record Slot(Object value, long expiresAt) {}
    private final int maxEntries;
    private final long ttlMillis;
    private final LinkedHashMap<String, Slot> entries;

    BoundedCache(int maxEntries, long ttlMillis) {
      this.maxEntries = maxEntries;
      this.ttlMillis = ttlMillis;
      this.entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) {
          return size() > BoundedCache.this.maxEntries;
        }
      };
    }

    @SuppressWarnings("unchecked")
    <T> T get(String key, Supplier<T> loader) {
      long now = System.currentTimeMillis();
      synchronized (entries) {
        var entry = entries.get(key);
        if (entry != null && entry.expiresAt() > now) return (T) entry.value();
      }
      T value = loader.get();
      synchronized (entries) { entries.put(key, new Slot(value, now + ttlMillis)); }
      return value;
    }

    int size() { synchronized (entries) { return entries.size(); } }
  }
}
