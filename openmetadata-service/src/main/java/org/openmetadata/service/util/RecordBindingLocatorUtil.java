/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openmetadata.service.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.openmetadata.schema.type.RecordLocator;
import org.openmetadata.schema.type.RecordLocatorValueType;

public final class RecordBindingLocatorUtil {
  private static final String TABLE_PRIMARY_KEY = "TABLE_PRIMARY_KEY";
  private static final String API_RESOURCE = "API_RESOURCE";
  private static final Set<String> FORBIDDEN_FIELDS =
      Set.of(
          "header",
          "headers",
          "auth",
          "authorization",
          "token",
          "apikey",
          "password",
          "secret",
          "accesstoken",
          "refreshtoken",
          "cookie",
          "setcookie");
  private static final Set<String> TABLE_FIELDS = Set.of("keys");
  private static final Set<String> API_FIELDS =
      Set.of("businessKey", "environment", "pathParameters");
  private static final Set<String> KEY_FIELDS = Set.of("fieldFqn", "valueType", "value");
  private static final Set<String> BUSINESS_KEY_FIELDS =
      Set.of("jsonPointer", "valueType", "value");
  private static final int MAX_DECIMAL_LITERAL_LENGTH = 4096;
  private static final int MAX_DECIMAL_PRECISION_OR_SCALE = 1024;
  private static final ObjectMapper CANONICAL_MAPPER =
      new ObjectMapper()
          .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

  private RecordBindingLocatorUtil() {}

  public static CanonicalLocator canonicalize(String locatorType, Map<String, Object> locator) {
    if (locatorType == null) {
      throw new IllegalArgumentException("locatorType is required");
    }
    if (locator == null) {
      throw new IllegalArgumentException("locator is required");
    }
    rejectCredentialFields(locator);

    Map<String, Object> canonicalLocator =
        switch (locatorType) {
          case TABLE_PRIMARY_KEY -> canonicalizeTable(locator);
          case API_RESOURCE -> canonicalizeApi(locator);
          default -> throw new IllegalArgumentException("Unsupported locatorType: " + locatorType);
        };
    String canonicalJson = toJson(canonicalLocator);
    return new CanonicalLocator(canonicalJson, sha256(locatorType + "\n" + canonicalJson));
  }

  public static RecordLocator parseCanonicalLocator(String canonicalJson) {
    if (canonicalJson == null) {
      throw new IllegalArgumentException("canonicalJson is required");
    }
    try {
      RecordLocator locator = CANONICAL_MAPPER.readValue(canonicalJson, RecordLocator.class);
      // JSON.parse/Axios would round high-precision JSON numbers in a browser. Keep NUMBER values
      // as their canonical decimal strings in API responses; valueType continues to carry the
      // logical type and canonicalize() accepts this lossless representation on a later resolve.
      if (locator.getKeys() != null) {
        for (var key : locator.getKeys()) {
          if (key.getValueType() == RecordLocatorValueType.NUMBER) {
            key.setValue(toPlainDecimalString(key.getValue()));
          }
        }
      }
      if (locator.getBusinessKey() != null
          && locator.getBusinessKey().getValueType() == RecordLocatorValueType.NUMBER) {
        locator
            .getBusinessKey()
            .setValue(toPlainDecimalString(locator.getBusinessKey().getValue()));
      }
      return locator;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Invalid canonical record locator", exception);
    }
  }

  private static String toPlainDecimalString(Object value) {
    if (value instanceof BigDecimal decimal) {
      return decimal.toPlainString();
    }
    if (value instanceof Number number) {
      return number.toString();
    }
    if (value instanceof String string) {
      return normalizeNumber(string, "Canonical NUMBER value").toPlainString();
    }
    throw new IllegalArgumentException("Canonical NUMBER value is not numeric");
  }

  private static Map<String, Object> canonicalizeTable(Map<String, Object> locator) {
    requireFields(locator, TABLE_FIELDS, "TABLE_PRIMARY_KEY locator");
    Object keysValue = locator.get("keys");
    if (!(keysValue instanceof List<?> keys) || keys.isEmpty()) {
      throw new IllegalArgumentException("TABLE_PRIMARY_KEY locator requires non-empty keys");
    }

    List<Map<String, Object>> canonicalKeys = new ArrayList<>(keys.size());
    Set<String> fieldFqns = new HashSet<>();
    for (int index = 0; index < keys.size(); index++) {
      Map<?, ?> key = requireMap(keys.get(index), "keys[" + index + "]");
      requireFields(key, KEY_FIELDS, "keys[" + index + "]");
      String fieldFqn = requireNonBlankString(key.get("fieldFqn"), "fieldFqn");
      if (!fieldFqns.add(fieldFqn)) {
        throw new IllegalArgumentException("Duplicate fieldFqn: " + fieldFqn);
      }
      canonicalKeys.add(canonicalTypedValue(key, "fieldFqn", fieldFqn, "keys[" + index + "]"));
    }
    canonicalKeys.sort(Comparator.comparing(key -> (String) key.get("fieldFqn")));

    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("keys", canonicalKeys);
    return canonical;
  }

  private static Map<String, Object> canonicalizeApi(Map<String, Object> locator) {
    if (locator.containsKey("keys")) {
      throw new IllegalArgumentException("API_RESOURCE locator must not contain keys");
    }
    requireAllowedFields(locator, API_FIELDS, "API_RESOURCE locator");
    if (!locator.containsKey("businessKey")) {
      throw new IllegalArgumentException("API_RESOURCE locator requires businessKey");
    }

    Map<?, ?> businessKey = requireMap(locator.get("businessKey"), "businessKey");
    requireFields(businessKey, BUSINESS_KEY_FIELDS, "businessKey");
    String jsonPointer = requireString(businessKey.get("jsonPointer"), "jsonPointer");
    validateJsonPointer(jsonPointer);

    Map<String, Object> canonical = new LinkedHashMap<>();
    if (locator.containsKey("environment")) {
      canonical.put("environment", requireString(locator.get("environment"), "environment"));
    }
    if (locator.containsKey("pathParameters")) {
      canonical.put("pathParameters", canonicalizePathParameters(locator.get("pathParameters")));
    }
    canonical.put(
        "businessKey", canonicalTypedValue(businessKey, "jsonPointer", jsonPointer, "businessKey"));
    return canonical;
  }

  private static Map<String, Object> canonicalTypedValue(
      Map<?, ?> source, String identityField, String identityValue, String path) {
    String valueType = requireString(source.get("valueType"), path + ".valueType");
    if (!source.containsKey("value") || source.get("value") == null) {
      throw new IllegalArgumentException(path + ".value is required");
    }

    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put(identityField, identityValue);
    canonical.put("valueType", valueType);
    canonical.put("value", normalizeValue(valueType, source.get("value"), path + ".value"));
    return canonical;
  }

  private static Object normalizeValue(String valueType, Object value, String path) {
    return switch (valueType) {
      case "STRING" -> requireString(value, path);
      case "NUMBER" -> normalizeNumber(value, path);
      case "BOOLEAN" -> {
        if (!(value instanceof Boolean)) {
          throw typeMismatch(path, "BOOLEAN");
        }
        yield value;
      }
      case "DATE" -> normalizeDate(value, path);
      case "DATETIME" -> normalizeDateTime(value, path);
      default -> throw new IllegalArgumentException("Unsupported valueType: " + valueType);
    };
  }

  private static BigDecimal normalizeNumber(Object value, String path) {
    String decimalText;
    if (value instanceof Number number) {
      decimalText = number.toString();
    } else if (value instanceof String string && !string.isBlank()) {
      decimalText = string;
    } else {
      throw typeMismatch(path, "NUMBER");
    }
    if (decimalText.length() > MAX_DECIMAL_LITERAL_LENGTH) {
      throw new IllegalArgumentException(path + " exceeds the supported decimal length");
    }
    try {
      BigDecimal normalized = new BigDecimal(decimalText).stripTrailingZeros();
      if (normalized.precision() > MAX_DECIMAL_PRECISION_OR_SCALE
          || Math.abs((long) normalized.scale()) > MAX_DECIMAL_PRECISION_OR_SCALE) {
        throw new IllegalArgumentException(path + " exceeds the supported decimal precision");
      }
      return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(path + " must be a finite decimal number", exception);
    }
  }

  private static String normalizeDate(Object value, String path) {
    String text = requireString(value, path);
    try {
      return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).toString();
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(path + " must be an ISO-8601 date", exception);
    }
  }

  private static String normalizeDateTime(Object value, String path) {
    String text = requireString(value, path);
    try {
      return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(
          path + " must be an ISO-8601 date-time with offset", exception);
    }
  }

  private static Map<String, String> canonicalizePathParameters(Object value) {
    Map<?, ?> parameters = requireMap(value, "pathParameters");
    Map<String, String> canonical = new TreeMap<>();
    for (Map.Entry<?, ?> entry : parameters.entrySet()) {
      String key = requireString(entry.getKey(), "pathParameters key");
      canonical.put(key, requireString(entry.getValue(), "pathParameters." + key));
    }
    return canonical;
  }

  private static void validateJsonPointer(String pointer) {
    if (!pointer.isEmpty() && pointer.charAt(0) != '/') {
      throw new IllegalArgumentException("jsonPointer must be empty or start with '/'");
    }
    for (int index = 0; index < pointer.length(); index++) {
      if (pointer.charAt(index) == '~'
          && (index + 1 == pointer.length()
              || (pointer.charAt(index + 1) != '0' && pointer.charAt(index + 1) != '1'))) {
        throw new IllegalArgumentException("jsonPointer contains an invalid escape");
      }
    }
  }

  private static void rejectCredentialFields(Object value) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() instanceof String key
            && FORBIDDEN_FIELDS.contains(normalizeSensitiveFieldName(key))) {
          throw new IllegalArgumentException("Credential field is not allowed: " + key);
        }
        rejectCredentialFields(entry.getValue());
      }
    } else if (value instanceof Iterable<?> iterable) {
      iterable.forEach(RecordBindingLocatorUtil::rejectCredentialFields);
    } else if (value != null && value.getClass().isArray()) {
      for (int index = 0; index < Array.getLength(value); index++) {
        rejectCredentialFields(Array.get(value, index));
      }
    }
  }

  private static String normalizeSensitiveFieldName(String fieldName) {
    return fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
  }

  private static Map<?, ?> requireMap(Object value, String path) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(path + " must be an object");
    }
    return map;
  }

  private static void requireFields(Map<?, ?> value, Set<String> required, String path) {
    requireAllowedFields(value, required, path);
    if (!value.keySet().containsAll(required)) {
      throw new IllegalArgumentException(path + " requires fields " + required);
    }
  }

  private static void requireAllowedFields(Map<?, ?> value, Set<String> allowed, String path) {
    for (Object field : value.keySet()) {
      if (!(field instanceof String name) || !allowed.contains(name)) {
        throw new IllegalArgumentException(path + " contains unsupported field: " + field);
      }
    }
  }

  private static String requireString(Object value, String path) {
    if (!(value instanceof String string)) {
      throw new IllegalArgumentException(path + " must be a string");
    }
    return string;
  }

  private static String requireNonBlankString(Object value, String path) {
    String string = requireString(value, path);
    if (string.isBlank()) {
      throw new IllegalArgumentException(path + " must not be blank");
    }
    return string;
  }

  private static IllegalArgumentException typeMismatch(String path, String valueType) {
    return new IllegalArgumentException(path + " does not match valueType " + valueType);
  }

  private static String toJson(Map<String, Object> value) {
    try {
      return CANONICAL_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize canonical locator", exception);
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record CanonicalLocator(String canonicalJson, String locatorHash) {}
}
