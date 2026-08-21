package com.logali.rfcguard;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Properties;

final class JcoReflection {
  private JcoReflection() {}

  static Object invokeStatic(String className, String method, Object... arguments) {
    try { return invoke(Class.forName(className), null, method, arguments); }
    catch (ClassNotFoundException error) {
      throw new IllegalStateException("SAP JCo is not installed in /opt/sap/jco", error);
    }
  }

  static Object invoke(Object target, String method, Object... arguments) {
    return invoke(target.getClass(), target, method, arguments);
  }

  static void registerProvider(String destinationName, Properties properties) {
    try {
      Class<?> providerType = Class.forName("com.sap.conn.jco.ext.DestinationDataProvider");
      Object proxy = Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType}, (ignored, method, args) -> {
        return switch (method.getName()) {
          case "getDestinationProperties" -> destinationName.equals(args[0]) ? properties : null;
          case "supportsEvents" -> false;
          case "setDestinationDataEventListener" -> null;
          case "toString" -> "SapRfcGuardDestinationProvider";
          case "hashCode" -> System.identityHashCode(ignored);
          case "equals" -> ignored == args[0];
          default -> null;
        };
      });
      Class<?> environment = Class.forName("com.sap.conn.jco.ext.Environment");
      environment.getMethod("registerDestinationDataProvider", providerType).invoke(null, proxy);
    } catch (ClassNotFoundException error) {
      throw new IllegalStateException("SAP JCo is not installed in /opt/sap/jco", error);
    } catch (ReflectiveOperationException error) {
      throw unwrap(error);
    }
  }

  static String string(Object record, String field) {
    Object value = invoke(record, "getString", field);
    return value == null ? "" : value.toString().trim();
  }

  private static Object invoke(Class<?> type, Object target, String method, Object... arguments) {
    Method candidate = Arrays.stream(type.getMethods())
        .filter(item -> item.getName().equals(method) && item.getParameterCount() == arguments.length)
        .filter(item -> compatible(item.getParameterTypes(), arguments))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Unsupported JCo method: " + type.getName() + "." + method));
    try { return candidate.invoke(target, arguments); }
    catch (IllegalAccessException | InvocationTargetException error) { throw unwrap(error); }
  }

  private static boolean compatible(Class<?>[] types, Object[] arguments) {
    for (int index = 0; index < types.length; index++) {
      if (arguments[index] == null) continue;
      Class<?> expected = boxed(types[index]);
      if (!expected.isAssignableFrom(arguments[index].getClass())) return false;
    }
    return true;
  }

  private static Class<?> boxed(Class<?> type) {
    if (!type.isPrimitive()) return type;
    if (type == int.class) return Integer.class;
    if (type == boolean.class) return Boolean.class;
    if (type == long.class) return Long.class;
    if (type == double.class) return Double.class;
    if (type == float.class) return Float.class;
    if (type == short.class) return Short.class;
    if (type == byte.class) return Byte.class;
    if (type == char.class) return Character.class;
    return type;
  }

  private static IllegalStateException unwrap(Exception error) {
    Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
    return new IllegalStateException(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
  }
}
