/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.type.RecordLocator;

class RecordBindingLocatorUtilContractTest {
  @Test
  void fr001Ac003TableKeyOrderDoesNotChangeHash() throws Exception {
    Map<String, Object> first = Map.of("keys", List.of(key("site", "A"), key("equipmentId", 7)));
    Map<String, Object> second = Map.of("keys", List.of(key("equipmentId", 7), key("site", "A")));

    assertEquals(
        hash(canonicalize("TABLE_PRIMARY_KEY", first)),
        hash(canonicalize("TABLE_PRIMARY_KEY", second)));
  }

  @Test
  void canonicalHashRemainsBackwardCompatible() throws Exception {
    Object canonical =
        canonicalize(
            "TABLE_PRIMARY_KEY",
            Map.of("keys", List.of(key("service.db.schema.equipment.id", "A"))));

    assertEquals(
        "904d6d6d38c581f80ffa4d6b57d4ebf1f966be4bc6ab6b8310a766b762dd717e", hash(canonical));
  }

  @Test
  void fr001Bc001TypedValuesProduceDifferentHashes() throws Exception {
    Object stringValue = canonicalize("TABLE_PRIMARY_KEY", Map.of("keys", List.of(key("id", "1"))));
    Object numberValue = canonicalize("TABLE_PRIMARY_KEY", Map.of("keys", List.of(key("id", 1))));

    assertNotEquals(hash(stringValue), hash(numberValue));
  }

  @Test
  void numberStringsRetainDecimalPrecision() throws Exception {
    Object first =
        canonicalize(
            "TABLE_PRIMARY_KEY",
            Map.of("keys", List.of(typedKey("id", "9007199254740992.0", "NUMBER"))));
    Object second =
        canonicalize(
            "TABLE_PRIMARY_KEY",
            Map.of("keys", List.of(typedKey("id", "9007199254740993.0", "NUMBER"))));

    assertNotEquals(hash(first), hash(second));
  }

  @Test
  void canonicalRoundTripRetainsFractionalPrecision() throws Exception {
    Object canonical =
        canonicalize(
            "TABLE_PRIMARY_KEY",
            Map.of("keys", List.of(typedKey("id", "9007199254740993.1", "NUMBER"))));
    String canonicalJson =
        (String) canonical.getClass().getMethod("canonicalJson").invoke(canonical);

    RecordLocator locator = RecordBindingLocatorUtil.parseCanonicalLocator(canonicalJson);
    String storageJson = org.openmetadata.schema.utils.JsonUtils.pojoToJson(locator);
    RecordLocator storedLocator = RecordBindingLocatorUtil.parseCanonicalLocator(storageJson);

    assertEquals("9007199254740993.1", locator.getKeys().getFirst().getValue());
    assertTrue(storageJson.contains("\"value\":\"9007199254740993.1\""));
    assertEquals("9007199254740993.1", storedLocator.getKeys().getFirst().getValue());
    assertEquals(
        hash(canonical),
        RecordBindingLocatorUtil.canonicalize("TABLE_PRIMARY_KEY", locatorMap(storedLocator))
            .locatorHash());
  }

  @Test
  void rejectsPathologicalDecimalExponents() {
    InvocationTargetException exception =
        assertThrows(
            InvocationTargetException.class,
            () ->
                canonicalize(
                    "TABLE_PRIMARY_KEY",
                    Map.of("keys", List.of(typedKey("id", "1e10000000", "NUMBER")))));

    assertEquals(IllegalArgumentException.class, exception.getCause().getClass());
  }

  @Test
  void fr001Sec002RejectsCredentialFields() {
    InvocationTargetException exception =
        assertThrows(
            InvocationTargetException.class,
            () -> canonicalize("API_RESOURCE", Map.of("token", "secret")));

    assertEquals(IllegalArgumentException.class, exception.getCause().getClass());
    assertTrue(exception.getCause().getMessage().contains("Credential field"));
  }

  @Test
  void fr001Sec003RejectsNestedAuthFieldsCaseInsensitively() {
    InvocationTargetException exception =
        assertThrows(
            InvocationTargetException.class,
            () ->
                canonicalize(
                    "API_RESOURCE",
                    Map.of(
                        "businessKey",
                        Map.of(
                            "jsonPointer", "/id",
                            "valueType", "STRING",
                            "value", "A",
                            "AuTh", "must-not-persist"))));

    assertEquals(IllegalArgumentException.class, exception.getCause().getClass());
    assertTrue(exception.getCause().getMessage().contains("Credential field"));
  }

  private Map<String, Object> key(String fieldFqn, Object value) {
    return typedKey(fieldFqn, value, value instanceof Number ? "NUMBER" : "STRING");
  }

  private Map<String, Object> typedKey(String fieldFqn, Object value, String valueType) {
    return Map.of("fieldFqn", fieldFqn, "valueType", valueType, "value", value);
  }

  private Object canonicalize(String locatorType, Map<String, Object> locator) throws Exception {
    Class<?> utility = Class.forName("org.openmetadata.service.util.RecordBindingLocatorUtil");
    Method method = utility.getMethod("canonicalize", String.class, Map.class);
    return method.invoke(null, locatorType, locator);
  }

  private String hash(Object result) throws Exception {
    return (String) result.getClass().getMethod("locatorHash").invoke(result);
  }

  private Map<String, Object> locatorMap(RecordLocator locator) {
    return org.openmetadata.schema.utils.JsonUtils.getMap(locator);
  }
}
