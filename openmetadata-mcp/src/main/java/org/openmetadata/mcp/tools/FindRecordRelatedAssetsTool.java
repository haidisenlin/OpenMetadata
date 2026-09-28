package org.openmetadata.mcp.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.openmetadata.mcp.util.McpParams;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.entity.data.APIEndpoint;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.schema.type.RecordBinding;
import org.openmetadata.schema.type.TagLabel;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.RecordBindingRepository;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/** Resolves a record's terms and returns evidence for their related assets and records. */
public class FindRecordRelatedAssetsTool implements McpTool {
  private static final int MAX_BINDINGS = 10;
  private static final int MAX_TERMS = 100;

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params)
      throws IOException {
    int assetLimit = boundedInt(params, "assetLimit", 20, 50);
    int assetOffset = nonNegativeInt(params, "assetOffset");
    int recordLimit = boundedInt(params, "recordLimit", 10, 50);
    int recordOffset = nonNegativeInt(params, "recordOffset");
    String targetType = McpParams.getString(params, "targetEntityType", null);
    if (targetType != null && !List.of(Entity.TABLE, Entity.API_ENDPOINT).contains(targetType)) {
      throw new IllegalArgumentException("targetEntityType must be table or apiEndpoint");
    }

    ResolveRecordBindingTool.Resolution resolved =
        new ResolveRecordBindingTool().resolve(authorizer, securityContext, params);
    List<RecordBinding> sourceBindings = resolved.bindings();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceAsset", resolved.asset());
    result.put("bindings", sourceBindings.stream().limit(MAX_BINDINGS).toList());
    result.put("matchType", "EXACT_RECORD_LOCATOR");
    result.put("sourceRecordVerified", false);
    List<Map<String, Object>> graphs = new ArrayList<>();
    Map<UUID, EntityReference> terms = new LinkedHashMap<>();
    boolean truncated = sourceBindings.size() > MAX_BINDINGS;
    for (RecordBinding binding : sourceBindings.stream().limit(MAX_BINDINGS).toList()) {
      Map<String, Object> graphParams = new LinkedHashMap<>(params);
      graphParams.remove("fqn");
      graphParams.put("termId", binding.getTerm().getId().toString());
      graphParams.put("maxNodes", Math.min(50, MAX_TERMS - terms.size()));
      Map<String, Object> graph =
          new GetTermRelationGraphTool().execute(authorizer, securityContext, graphParams);
      graphs.add(graph);
      for (Object node : (List<?>) graph.get("nodes")) {
        EntityReference term = JsonUtils.convertValue(node, EntityReference.class);
        terms.put(term.getId(), term);
      }
      truncated |= Boolean.TRUE.equals(graph.get("truncated"));
      if (terms.size() >= MAX_TERMS) {
        truncated = true;
        break;
      }
    }
    result.put("graphs", graphs);
    List<Map<String, Object>> assets = new ArrayList<>();
    List<Map<String, Object>> records = new ArrayList<>();
    boolean assetsHasMore = false;
    List<Map<String, Object>> recordPages = new ArrayList<>();
    if (!terms.isEmpty()) {
      Map<String, Object> searchParams =
          assetSearchParams(terms.values().stream().toList(), targetType, assetLimit, assetOffset);
      Map<String, Object> search =
          new SearchMetadataTool().execute(authorizer, securityContext, searchParams);
      assetsHasMore = Boolean.TRUE.equals(search.get("hasMore"));
      if (search.get("results") instanceof List<?> hits) {
        for (Object hit : hits) {
          Map<?, ?> candidate = JsonUtils.convertValue(hit, Map.class);
          String type = (String) candidate.get("entityType");
          String fqn = (String) candidate.get("fullyQualifiedName");
          if (!List.of(Entity.TABLE, Entity.API_ENDPOINT).contains(type) || fqn == null) {
            continue;
          }
          try {
            EntityInterface asset = readVisibleAsset(authorizer, securityContext, type, fqn);
            List<EntityReference> matched = matchedTerms(asset.getTags(), terms);
            if (!matched.isEmpty()) {
              assets.add(assetEvidence(asset, matched));
            }
          } catch (AuthorizationException | EntityNotFoundException ignored) {
            // Search may lag a deletion or a policy update; never return the stale hit.
          }
        }
      }
      RecordBindingRepository repository = new RecordBindingRepository(Entity.getCollectionDAO());
      for (EntityReference term : terms.values()) {
        ResultList<RecordBinding> page = repository.list(term.getId(), recordLimit, recordOffset);
        for (RecordBinding record : page.getData()) {
          EntityReference asset = record.getAsset();
          if (targetType != null && !targetType.equals(asset.getType())) {
            continue;
          }
          try {
            authorize(
                authorizer,
                securityContext,
                asset.getType(),
                asset.getId(),
                asset.getFullyQualifiedName());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("binding", record);
            evidence.put("association", "RECORD_BINDING");
            records.add(evidence);
          } catch (AuthorizationException ignored) {
            // A visible term does not grant permission to its bound assets.
          }
        }
        if (page.getPaging().getTotal() > recordOffset + page.getData().size()) {
          recordPages.add(
              Map.of("termId", term.getId(), "nextRecordOffset", recordOffset + recordLimit));
        }
      }
    }
    result.put("assets", assets);
    result.put("records", records);
    result.put("assetsHasMore", assetsHasMore);
    if (assetsHasMore) {
      result.put("nextAssetOffset", assetOffset + assetLimit);
    }
    result.put("recordPages", recordPages);
    result.put("truncated", truncated || assetsHasMore || !recordPages.isEmpty());
    result.put(
        "message",
        sourceBindings.isEmpty()
            ? "No visible exact record binding found. Check the source asset and typed locator; descriptions are not used as a fallback."
            : "Bindings and directed glossary relations are metadata evidence. Source records and API responses were not fetched. Preserve UNVERIFIED/STALE status; a tag associates an asset, not every row in it.");
    return result;
  }

  static Map<String, Object> assetSearchParams(
      List<EntityReference> terms, String targetType, int limit, int offset) {
    List<String> names =
        terms.stream()
            .map(EntityReference::getFullyQualifiedName)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
    List<String> types =
        targetType == null ? List.of(Entity.TABLE, Entity.API_ENDPOINT) : List.of(targetType);
    Map<String, Object> filter =
        Map.of(
            "bool",
            Map.of(
                "filter",
                List.of(
                    Map.of("terms", Map.of("tags.tagFQN", names)),
                    Map.of("terms", Map.of("entityType", types)))));
    return Map.of("query", "*", "queryFilter", filter, "size", limit, "from", offset);
  }

  static List<EntityReference> matchedTerms(List<TagLabel> tags, Map<UUID, EntityReference> terms) {
    if (tags == null) {
      return List.of();
    }
    return terms.values().stream()
        .filter(
            term ->
                tags.stream()
                    .anyMatch(
                        tag ->
                            tag.getSource() == TagLabel.TagSource.GLOSSARY
                                && term.getFullyQualifiedName().equals(tag.getTagFQN())))
        .toList();
  }

  static Map<String, Object> assetEvidence(EntityInterface asset, List<EntityReference> terms) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("asset", asset.getEntityReference());
    evidence.put("matchedTerms", terms);
    evidence.put("association", "GLOSSARY_TAG");
    if (asset instanceof APIEndpoint endpoint) {
      evidence.put("endpointURL", endpoint.getEndpointURL());
      evidence.put("requestMethod", endpoint.getRequestMethod());
    }
    return evidence;
  }

  static EntityInterface readVisibleAsset(
      Authorizer authorizer, CatalogSecurityContext context, String type, String fqn) {
    authorize(authorizer, context, type, null, fqn);
    return Entity.getEntityByName(type, fqn, "tags", Include.NON_DELETED);
  }

  private static void authorize(
      Authorizer authorizer, CatalogSecurityContext context, String type, UUID id, String fqn) {
    authorizer.authorize(
        context,
        new OperationContext(type, MetadataOperation.VIEW_ALL),
        new ResourceContext<>(type, id, fqn));
  }

  private static int boundedInt(Map<String, Object> params, String key, int defaultValue, int max) {
    int value = McpParams.getInt(params, key, defaultValue);
    if (value < 1 || value > max) {
      throw new IllegalArgumentException(key + " must be between 1 and " + max);
    }
    return value;
  }

  private static int nonNegativeInt(Map<String, Object> params, String key) {
    int value = McpParams.getInt(params, key, 0);
    if (value < 0 || value > 10000) {
      throw new IllegalArgumentException(key + " must be between 0 and 10000");
    }
    return value;
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext context,
      Map<String, Object> params)
      throws IOException {
    return execute(authorizer, context, params);
  }
}
