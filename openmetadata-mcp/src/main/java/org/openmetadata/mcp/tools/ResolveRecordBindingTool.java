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

import static org.openmetadata.schema.type.MetadataOperation.VIEW_ALL;

import jakarta.ws.rs.ForbiddenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openmetadata.schema.api.data.CreateGlossaryRecordBinding;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.RecordBinding;
import org.openmetadata.schema.type.RecordLocatorType;
import org.openmetadata.service.Entity;
import org.openmetadata.service.jdbi3.RecordBindingRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;
import org.openmetadata.service.util.RecordBindingLocatorUtil;

public class ResolveRecordBindingTool implements McpTool {

  public record Resolution(EntityReference asset, List<RecordBinding> bindings) {
    public Resolution {
      bindings = List.copyOf(bindings);
    }
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    Resolution resolution = resolve(authorizer, securityContext, params);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("asset", resolution.asset());
    result.put("bindings", resolution.bindings());
    result.put("count", resolution.bindings().size());
    result.put("matchType", "EXACT_RECORD_LOCATOR");
    result.put("sourceRecordVerified", false);
    result.put(
        "message",
        "Returns stored glossary bindings visible to the caller using exact typed record locators. "
            + "Binding status is preserved; this lookup does not query the source table or API "
            + "to verify that the record exists. No matches does not prove the source record is absent.");
    return result;
  }

  /** Resolves stored record bindings with the same locator rules and permissions as the REST API. */
  public Resolution resolve(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    CreateGlossaryRecordBinding request = toRequest(params);
    authorizeView(authorizer, securityContext, request.getAsset());
    EntityReference asset = Entity.getEntityReference(request.getAsset(), Include.NON_DELETED);
    request.setAsset(asset);
    List<RecordBinding> bindings =
        new RecordBindingRepository(Entity.getCollectionDAO()).resolve(request);
    return new Resolution(asset, visibleBindings(authorizer, securityContext, bindings));
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    return execute(authorizer, securityContext, params);
  }

  static CreateGlossaryRecordBinding toRequest(Map<String, Object> params) {
    if (params == null) {
      throw new IllegalArgumentException("params are required");
    }
    String assetType = requiredString(params, "assetType");
    if (!Entity.TABLE.equals(assetType) && !Entity.API_ENDPOINT.equals(assetType)) {
      throw new IllegalArgumentException("assetType must be table or apiEndpoint");
    }
    String assetId = optionalString(params, "assetId");
    String assetFqn = optionalString(params, "assetFqn");
    if ((assetId == null) == (assetFqn == null)) {
      throw new IllegalArgumentException("Provide exactly one of assetId or assetFqn");
    }
    EntityReference asset = new EntityReference().withType(assetType);
    if (assetId == null) {
      asset.setFullyQualifiedName(assetFqn);
    } else {
      UUID id;
      try {
        id = UUID.fromString(assetId);
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("assetId must be a UUID", exception);
      }
      if (!id.toString().equalsIgnoreCase(assetId)) {
        throw new IllegalArgumentException("assetId must be a UUID");
      }
      asset.setId(id);
    }

    String locatorType = requiredString(params, "locatorType");
    RecordLocatorType expectedLocatorType =
        Entity.TABLE.equals(assetType)
            ? RecordLocatorType.TABLE_PRIMARY_KEY
            : RecordLocatorType.API_RESOURCE;
    if (!expectedLocatorType.value().equals(locatorType)) {
      throw new IllegalArgumentException(
          "assetType " + assetType + " requires locatorType " + expectedLocatorType.value());
    }
    if (!(params.get("locator") instanceof Map<?, ?> rawLocator)) {
      throw new IllegalArgumentException("locator must be an object");
    }
    Map<String, Object> locator = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawLocator.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("locator property names must be strings");
      }
      locator.put(key, entry.getValue());
    }
    rejectFloatingPointKeys(locator);
    var canonical = RecordBindingLocatorUtil.canonicalize(locatorType, locator);
    return new CreateGlossaryRecordBinding()
        .withAsset(asset)
        .withLocatorType(expectedLocatorType)
        .withLocator(RecordBindingLocatorUtil.parseCanonicalLocator(canonical.canonicalJson()));
  }

  private static void rejectFloatingPointKeys(Map<String, Object> locator) {
    if (locator.get("keys") instanceof List<?> keys) {
      for (Object key : keys) {
        rejectFloatingPointKey(key);
      }
    }
    rejectFloatingPointKey(locator.get("businessKey"));
  }

  private static void rejectFloatingPointKey(Object key) {
    if (key instanceof Map<?, ?> fields
        && "NUMBER".equals(fields.get("valueType"))
        && (fields.get("value") instanceof Double || fields.get("value") instanceof Float)) {
      throw new IllegalArgumentException(
          "NUMBER locator values must use decimal strings or exact integers; floating-point "
              + "JSON values may lose record identifier precision. Send the value as a string.");
    }
  }

  static List<RecordBinding> visibleBindings(
      Authorizer authorizer,
      CatalogSecurityContext securityContext,
      List<RecordBinding> bindings) {
    List<RecordBinding> visible = new ArrayList<>();
    for (RecordBinding binding : bindings) {
      EntityReference term = binding.getTerm();
      if (term == null || term.getId() == null || !Entity.GLOSSARY_TERM.equals(term.getType())) {
        continue;
      }
      try {
        authorizeView(authorizer, securityContext, term);
        visible.add(binding);
      } catch (AuthorizationException | ForbiddenException ignored) {
        // Do not disclose the presence or count of bindings to inaccessible glossary terms.
      }
    }
    return visible;
  }

  private static void authorizeView(
      Authorizer authorizer, CatalogSecurityContext securityContext, EntityReference entity) {
    authorizer.authorize(
        securityContext,
        new OperationContext(entity.getType(), VIEW_ALL),
        new ResourceContext<>(
            entity.getType(), entity.getId(), entity.getFullyQualifiedName()));
  }

  private static String requiredString(Map<String, Object> params, String name) {
    String value = optionalString(params, name);
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static String optionalString(Map<String, Object> params, String name) {
    Object value = params.get(name);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String string) || string.isBlank()) {
      throw new IllegalArgumentException(name + " must be a non-empty string");
    }
    return string;
  }
}
