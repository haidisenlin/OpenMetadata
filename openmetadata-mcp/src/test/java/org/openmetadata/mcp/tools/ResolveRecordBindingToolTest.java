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
package org.openmetadata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.api.data.CreateGlossaryRecordBinding;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.RecordBinding;
import org.openmetadata.schema.type.RecordBindingStatus;
import org.openmetadata.schema.type.RecordLocatorType;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.util.RecordBindingLocatorUtil;

class ResolveRecordBindingToolTest {
  private static final String ASSET_FQN = "factory.default.factory.equipment";
  private static final String FIELD_FQN = ASSET_FQN + ".id";

  @Test
  void tableLocatorPreservesExactStringAndAssetFqn() {
    CreateGlossaryRecordBinding request =
        ResolveRecordBindingTool.toRequest(tableParams("STRING", "EQ-A"));

    assertEquals(Entity.TABLE, request.getAsset().getType());
    assertEquals(ASSET_FQN, request.getAsset().getFullyQualifiedName());
    assertNull(request.getAsset().getId());
    assertEquals(RecordLocatorType.TABLE_PRIMARY_KEY, request.getLocatorType());
    assertEquals("EQ-A", request.getLocator().getKeys().getFirst().getValue());
    assertNotEquals(
        hash(request),
        hash(ResolveRecordBindingTool.toRequest(tableParams("STRING", "EQ_A"))));
  }

  @Test
  void compositeKeysAreOrderIndependentWithoutLosingNumericPrecision() {
    Map<String, Object> number = key(FIELD_FQN, "NUMBER", "9007199254740993.100");
    Map<String, Object> tenant = key(ASSET_FQN + ".tenant", "STRING", "factory-a");
    Map<String, Object> first = tableParams("STRING", "unused");
    first.put("locator", Map.of("keys", List.of(number, tenant)));
    Map<String, Object> reversed = new HashMap<>(first);
    reversed.put("locator", Map.of("keys", List.of(tenant, number)));

    CreateGlossaryRecordBinding request = ResolveRecordBindingTool.toRequest(first);
    assertEquals(hash(request), hash(ResolveRecordBindingTool.toRequest(reversed)));
    assertEquals("9007199254740993.1", request.getLocator().getKeys().getFirst().getValue());
    assertNotEquals(
        hash(ResolveRecordBindingTool.toRequest(tableParams("NUMBER", "9007199254740993.1"))),
        hash(ResolveRecordBindingTool.toRequest(tableParams("NUMBER", "9007199254740992.1"))));
  }

  @Test
  void typedStringAndNumberDoNotCollapseToTheSameBinding() {
    assertNotEquals(
        hash(ResolveRecordBindingTool.toRequest(tableParams("STRING", "123"))),
        hash(ResolveRecordBindingTool.toRequest(tableParams("NUMBER", "123"))));
    assertEquals(
        true,
        ResolveRecordBindingTool.toRequest(tableParams("BOOLEAN", true))
            .getLocator()
            .getKeys()
            .getFirst()
            .getValue());
    assertThrows(
        IllegalArgumentException.class,
        () -> ResolveRecordBindingTool.toRequest(tableParams("BOOLEAN", "true")));
  }

  @Test
  void floatingPointRecordIdsMustBeSentAsStringsToAvoidRoundedMatches() {
    for (Number value : List.of(12.5d, 12.5f, 9007199254740993.1d)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ResolveRecordBindingTool.toRequest(tableParams("NUMBER", value)));
    }
    assertEquals(
        hash(ResolveRecordBindingTool.toRequest(tableParams("NUMBER", 123L))),
        hash(ResolveRecordBindingTool.toRequest(tableParams("NUMBER", "123"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ResolveRecordBindingTool.toRequest(
                Map.of(
                    "assetType", Entity.API_ENDPOINT,
                    "assetFqn", "api.equipment",
                    "locatorType", "API_RESOURCE",
                    "locator", Map.of("businessKey", Map.of("jsonPointer", "/id", "valueType", "NUMBER", "value", 1.5d)))));
  }

  @Test
  void apiLocatorAcceptsBusinessKeyEnvironmentAndPathParameters() {
    UUID assetId = UUID.randomUUID();
    CreateGlossaryRecordBinding request =
        ResolveRecordBindingTool.toRequest(
            Map.of(
                "assetType",
                Entity.API_ENDPOINT,
                "assetId",
                assetId.toString(),
                "locatorType",
                "API_RESOURCE",
                "locator",
                Map.of(
                    "businessKey",
                    Map.of("jsonPointer", "/equipment/id", "valueType", "STRING", "value", "EQ-A"),
                    "environment",
                    "production",
                    "pathParameters",
                    Map.of("equipmentId", "EQ-A"))));

    assertEquals(assetId, request.getAsset().getId());
    assertEquals(RecordLocatorType.API_RESOURCE, request.getLocatorType());
    assertNull(request.getLocator().getKeys());
    assertEquals("production", request.getLocator().getEnvironment());
    assertEquals(Map.of("equipmentId", "EQ-A"), request.getLocator().getPathParameters());
    assertEquals("/equipment/id", request.getLocator().getBusinessKey().getJsonPointer());
    assertEquals("EQ-A", request.getLocator().getBusinessKey().getValue());
  }

  @Test
  void requiresExactlyOneUnambiguousAssetIdentifier() {
    Map<String, Object> params = tableParams("STRING", "EQ-A");
    params.put("assetId", UUID.randomUUID().toString());
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
    params.remove("assetFqn");
    params.put("assetId", "1-1-1-1-1");
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
    params.remove("assetId");
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
  }

  @Test
  void rejectsAssetLocatorMismatchAndInventedResourceId() {
    Map<String, Object> params = tableParams("STRING", "EQ-A");
    params.put("locatorType", "API_RESOURCE");
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
    params.put("assetType", Entity.API_ENDPOINT);
    params.put("locator", Map.of("resourceId", "EQ-A"));
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
  }

  @Test
  void rejectsUnknownFieldsAndDuplicateCompositeKeysBeforeModelConversion() {
    Map<String, Object> params = tableParams("STRING", "EQ-A");
    params.put("locator", Map.of("keys", List.of(key(FIELD_FQN, "STRING", "EQ-A")), "sql", "1=1"));
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
    params.put(
        "locator",
        Map.of("keys", List.of(key(FIELD_FQN, "STRING", "EQ-A"), key(FIELD_FQN, "STRING", "EQ-B"))));
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
    params.put("locator", Map.of("keys", List.of()));
    assertThrows(IllegalArgumentException.class, () -> ResolveRecordBindingTool.toRequest(params));
  }

  @Test
  void deniedAssetNeverReachesRecordLookup() {
    Authorizer authorizer = mock(Authorizer.class);
    doThrow(new AuthorizationException("denied")).when(authorizer).authorize(any(), any(), any());
    try (MockedStatic<Entity> entities = mockStatic(Entity.class)) {
      assertThrows(
          AuthorizationException.class,
          () -> new ResolveRecordBindingTool().resolve(authorizer, null, tableParams("STRING", "EQ-A")));
      entities.verify(Entity::getCollectionDAO, never());
    }
  }

  @Test
  void inaccessibleTermsAreOmittedAndUnverifiedStatusIsPreserved() {
    RecordBinding binding =
        new RecordBinding()
            .withTerm(new EntityReference().withId(UUID.randomUUID()).withType(Entity.GLOSSARY_TERM))
            .withStatus(RecordBindingStatus.UNVERIFIED);
    Authorizer authorizer = mock(Authorizer.class);
    try (MockedStatic<Entity> ignored = mockStatic(Entity.class)) {
      List<RecordBinding> visible =
          ResolveRecordBindingTool.visibleBindings(authorizer, null, List.of(binding));
      assertEquals(List.of(binding), visible);
      assertEquals(RecordBindingStatus.UNVERIFIED, visible.getFirst().getStatus());

      doThrow(new AuthorizationException("denied")).when(authorizer).authorize(any(), any(), any());
      assertTrue(
          ResolveRecordBindingTool.visibleBindings(authorizer, null, List.of(binding)).isEmpty());
      assertTrue(
          ResolveRecordBindingTool.visibleBindings(authorizer, null, List.of(new RecordBinding()))
              .isEmpty());
    }
  }

  private static Map<String, Object> tableParams(String valueType, Object value) {
    return new HashMap<>(
        Map.of(
            "assetType",
            Entity.TABLE,
            "assetFqn",
            ASSET_FQN,
            "locatorType",
            "TABLE_PRIMARY_KEY",
            "locator",
            Map.of("keys", List.of(key(FIELD_FQN, valueType, value)))));
  }

  private static Map<String, Object> key(String fieldFqn, String valueType, Object value) {
    return Map.of("fieldFqn", fieldFqn, "valueType", valueType, "value", value);
  }

  private static String hash(CreateGlossaryRecordBinding request) {
    return RecordBindingLocatorUtil.canonicalize(
            request.getLocatorType().value(), JsonUtils.getMap(request.getLocator()))
        .locatorHash();
  }
}
