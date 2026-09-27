package org.openmetadata.mcp.tools;

import static org.openmetadata.schema.type.MetadataOperation.VIEW_BASIC;
import static org.openmetadata.service.Entity.GLOSSARY_TERM;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openmetadata.mcp.util.McpParams;
import org.openmetadata.schema.configuration.GlossaryTermRelationSettings;
import org.openmetadata.schema.configuration.GlossaryTermRelationType;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.settings.SettingsType;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.limits.Limits;
import org.openmetadata.service.resources.settings.SettingsCache;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/** Reads a bounded graph of the caller-visible semantic relationships between glossary terms. */
public class GetTermRelationGraphTool implements McpTool {
  static final int MAX_INSPECTED_RELATIONS = 1000;
  static final int MAX_EDGES = 500;

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer, CatalogSecurityContext securityContext, Map<String, Object> params) {
    return execute(params, new CatalogGraphAccess(authorizer, securityContext));
  }

  @Override
  public Map<String, Object> execute(
      Authorizer authorizer,
      Limits limits,
      CatalogSecurityContext securityContext,
      Map<String, Object> params) {
    return execute(authorizer, securityContext, params);
  }

  static Map<String, Object> execute(Map<String, Object> params, GraphAccess access) {
    String termId = McpParams.getString(params, "termId", null);
    String fqn = McpParams.getString(params, "fqn", null);
    if ((termId == null) == (fqn == null)) {
      throw new IllegalArgumentException("Provide exactly one of termId or fqn");
    }
    int depth = boundedInteger(params, "depth", 1, 3);
    int maxNodes = boundedInteger(params, "maxNodes", 50, 100);
    String direction =
        McpParams.getString(params, "direction", "both").trim().toLowerCase(Locale.ROOT);
    if (!Set.of("outgoing", "incoming", "both").contains(direction)) {
      throw new IllegalArgumentException("direction must be outgoing, incoming, or both");
    }
    Set<String> relationTypes = new LinkedHashSet<>(McpParams.getStringList(params, "relationTypes"));
    if (relationTypes.size() > 20
        || relationTypes.stream().anyMatch(type -> !type.matches("[a-zA-Z][a-zA-Z0-9]*"))) {
      throw new IllegalArgumentException("relationTypes must contain at most 20 relation type names");
    }

    EntityReference root = slim(access.read(termId == null ? null : UUID.fromString(termId), fqn));
    Map<UUID, EntityReference> nodes = new LinkedHashMap<>();
    nodes.put(root.getId(), root);
    Map<UUID, EntityReference> readable = new HashMap<>(nodes);
    Set<UUID> unavailable = new HashSet<>();
    Set<SemanticEdge> edges = new LinkedHashSet<>();
    Deque<Visit> queue = new ArrayDeque<>();
    queue.add(new Visit(root.getId(), 0));
    int inspected = 0;
    boolean truncated = false;

    while (!queue.isEmpty()) {
      Visit current = queue.removeFirst();
      if (current.hops() >= depth) {
        continue;
      }
      int remaining = MAX_INSPECTED_RELATIONS - inspected;
      if (remaining <= 0) {
        truncated = true;
        break;
      }
      List<StoredRelation> relations = access.relations(current.id(), remaining + 1);
      if (relations.size() > remaining) {
        truncated = true;
      }
      for (StoredRelation relation : relations.subList(0, Math.min(relations.size(), remaining))) {
        inspected++;
        for (SemanticEdge edge : semanticEdges(relation, access)) {
          if (!matches(edge, current.id(), direction, relationTypes)) {
            continue;
          }
          UUID neighborId = edge.from().equals(current.id()) ? edge.to() : edge.from();
          if (unavailable.contains(neighborId)) {
            continue;
          }
          EntityReference neighbor = readable.get(neighborId);
          if (neighbor == null) {
            try {
              neighbor = slim(access.read(neighborId, null));
              readable.put(neighborId, neighbor);
            } catch (AuthorizationException
                | ForbiddenException
                | EntityNotFoundException
                | NotFoundException e) {
              unavailable.add(neighborId);
              continue;
            }
          }
          if (!edges.contains(edge) && edges.size() >= MAX_EDGES) {
            truncated = true;
            continue;
          }
          if (!nodes.containsKey(neighborId)) {
            if (nodes.size() >= maxNodes) {
              truncated = true;
              continue;
            }
            nodes.put(neighborId, neighbor);
            queue.addLast(new Visit(neighborId, current.hops() + 1));
          }
          edges.add(edge);
        }
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("root", root);
    result.put("nodes", new ArrayList<>(nodes.values()));
    result.put(
        "edges",
        edges.stream()
            .map(
                edge ->
                    Map.of(
                        "from", edge.from().toString(),
                        "to", edge.to().toString(),
                        "relationType", edge.relationType()))
            .toList());
    result.put("depth", depth);
    result.put("direction", direction);
    result.put("relationTypes", new ArrayList<>(relationTypes));
    result.put("maxNodes", maxNodes);
    result.put("truncated", truncated);
    result.put(
        "scope",
        "Caller-visible terms within the requested depth; inverse relationships follow glossary"
            + " relation settings. Hidden or deleted terms are not traversed. This graph does not"
            + " query source records or execute API endpoints.");
    return result;
  }

  private static boolean matches(
      SemanticEdge edge, UUID current, String direction, Set<String> relationTypes) {
    return (relationTypes.isEmpty() || relationTypes.contains(edge.relationType()))
        && switch (direction) {
          case "outgoing" -> edge.from().equals(current);
          case "incoming" -> edge.to().equals(current);
          default -> edge.from().equals(current) || edge.to().equals(current);
        };
  }

  private static List<SemanticEdge> semanticEdges(StoredRelation relation, GraphAccess access) {
    // RELATED_TO rows use UUID-canonical storage. The reverse semantic edge is the configured
    // inverse, or the same type when no inverse is configured, as in GlossaryTermRepository.
    String type = relation.relationType() == null ? "relatedTo" : relation.relationType();
    String inverse = access.inverse(type);
    return List.of(
        new SemanticEdge(relation.from(), relation.to(), type),
        new SemanticEdge(relation.to(), relation.from(), inverse == null ? type : inverse));
  }

  private static int boundedInteger(Map<String, Object> params, String key, int fallback, int max) {
    Object raw = params.get(key);
    if (raw == null) {
      return fallback;
    }
    try {
      int value = Integer.parseInt(raw.toString());
      if (value < 1 || value > max) {
        throw new NumberFormatException();
      }
      return value;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be an integer between 1 and " + max);
    }
  }

  private static EntityReference slim(EntityReference ref) {
    return new EntityReference()
        .withId(ref.getId())
        .withType(GLOSSARY_TERM)
        .withName(ref.getName())
        .withDisplayName(ref.getDisplayName())
        .withFullyQualifiedName(ref.getFullyQualifiedName());
  }

  interface GraphAccess {
    EntityReference read(UUID id, String fqn);

    List<StoredRelation> relations(UUID id, int limit);

    String inverse(String relationType);
  }

  record StoredRelation(UUID from, UUID to, String relationType) {}

  private record SemanticEdge(UUID from, UUID to, String relationType) {}

  private record Visit(UUID id, int hops) {}

  private record CatalogGraphAccess(Authorizer authorizer, CatalogSecurityContext securityContext)
      implements GraphAccess {
    @Override
    public EntityReference read(UUID id, String fqn) {
      authorizer.authorize(
          securityContext,
          new OperationContext(GLOSSARY_TERM, VIEW_BASIC),
          new ResourceContext<>(GLOSSARY_TERM, id, fqn));
      GlossaryTerm term =
          id == null
              ? Entity.getEntityByName(GLOSSARY_TERM, fqn, "", Include.NON_DELETED)
              : Entity.getEntity(GLOSSARY_TERM, id, "", Include.NON_DELETED);
      return term.getEntityReference();
    }

    @Override
    public List<StoredRelation> relations(UUID id, int limit) {
      return Entity.getCollectionDAO()
          .relationshipDAO()
          .findGlossaryTermRelations(id, Relationship.RELATED_TO.ordinal(), limit)
          .stream()
          .map(
              row ->
                  new StoredRelation(
                      UUID.fromString(row.getFromId()),
                      UUID.fromString(row.getToId()),
                      relationType(row.getJson())))
          .toList();
    }

    @Override
    public String inverse(String relationType) {
      GlossaryTermRelationSettings settings =
          SettingsCache.getSetting(
              SettingsType.GLOSSARY_TERM_RELATION_SETTINGS, GlossaryTermRelationSettings.class);
      if (settings == null || settings.getRelationTypes() == null) {
        return null;
      }
      return settings.getRelationTypes().stream()
          .filter(type -> relationType.equalsIgnoreCase(type.getName()))
          .map(GlossaryTermRelationType::getInverseRelation)
          .filter(inverse -> inverse != null && !inverse.isBlank())
          .findFirst()
          .orElse(null);
    }

    private static String relationType(String json) {
      if (json != null) {
        JsonNode value = JsonUtils.readTree(json).path("relationType");
        if (value.isTextual() && !value.asText().isBlank()) {
          return value.asText();
        }
      }
      return "relatedTo";
    }
  }
}
