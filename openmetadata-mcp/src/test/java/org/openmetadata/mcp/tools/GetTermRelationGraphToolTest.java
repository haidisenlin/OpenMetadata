package org.openmetadata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.mcp.tools.GetTermRelationGraphTool.GraphAccess;
import org.openmetadata.mcp.tools.GetTermRelationGraphTool.StoredRelation;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.security.AuthorizationException;

class GetTermRelationGraphToolTest {
  @Test
  void outgoingUsesSemanticInverseWhenRootIsTheStoredTarget() {
    Fixture fixture = new Fixture();
    UUID assembly = fixture.term("Assembly");
    UUID part = fixture.term("Part");
    fixture.relations.add(new StoredRelation(assembly, part, "hasPart"));

    Map<String, Object> result =
        fixture.execute(part, Map.of("direction", "outgoing", "relationTypes", List.of("partOf")));

    assertEquals(Set.of(part, assembly), ids(result));
    assertEquals(List.of(edge(part, assembly, "partOf")), edges(result));
    assertEquals(false, result.get("truncated"));
  }

  @Test
  void incomingFiltersBySourcePerspectiveAndPreservesDirection() {
    Fixture fixture = new Fixture();
    UUID assembly = fixture.term("Assembly");
    UUID part = fixture.term("Part");
    fixture.relations.add(new StoredRelation(assembly, part, "hasPart"));

    Map<String, Object> result =
        fixture.execute(part, Map.of("direction", "incoming", "relationTypes", List.of("hasPart")));

    assertEquals(List.of(edge(assembly, part, "hasPart")), edges(result));
    assertTrue(
        edges(
                fixture.execute(
                    part,
                    Map.of("direction", "incoming", "relationTypes", List.of("partOf"))))
            .isEmpty());
  }

  @Test
  void bothReturnsTypedInverseEdgesAndKeepsDifferentRelationTypes() {
    Fixture fixture = new Fixture();
    UUID a = fixture.term("A");
    UUID b = fixture.term("B");
    fixture.relations.add(new StoredRelation(a, b, "hasPart"));
    fixture.relations.add(new StoredRelation(a, b, "seeAlso"));

    Map<String, Object> result = fixture.execute(a, Map.of("depth", 3));

    assertEquals(
        Set.of(
            edge(a, b, "hasPart"),
            edge(b, a, "partOf"),
            edge(a, b, "seeAlso"),
            edge(b, a, "seeAlso")),
        new HashSet<>(edges(result)));
    assertEquals(4, edges(result).size());
    assertEquals(Set.of(a, b), ids(result));
  }

  @Test
  void cyclesTerminateAndBreadthFirstTraversalKeepsShorterPaths() {
    Fixture fixture = new Fixture();
    UUID a = fixture.term("A");
    UUID b = fixture.term("B");
    UUID c = fixture.term("C");
    UUID d = fixture.term("D");
    UUID e = fixture.term("E");
    fixture.relations.addAll(
        List.of(
            new StoredRelation(a, b, "relatedTo"),
            new StoredRelation(b, c, "relatedTo"),
            new StoredRelation(c, a, "relatedTo"),
            new StoredRelation(c, d, "relatedTo"),
            new StoredRelation(d, e, "relatedTo")));

    Map<String, Object> result = fixture.execute(a, Map.of("depth", 2));

    assertEquals(Set.of(a, b, c, d), ids(result));
    assertEquals(8, edges(result).size());
    assertEquals(Set.of(a, b, c), fixture.expanded);
    assertEquals(false, result.get("truncated"));
  }

  @Test
  void hiddenAndDeletedTermsAreNotReturnedOrTraversed() {
    Fixture fixture = new Fixture();
    UUID root = fixture.term("Root");
    UUID hidden = fixture.term("SecretName");
    UUID behindHidden = fixture.term("BehindSecret");
    UUID visible = fixture.term("Visible");
    UUID deleted = UUID.randomUUID();
    fixture.denied.add(hidden);
    fixture.relations.addAll(
        List.of(
            new StoredRelation(root, hidden, "relatedTo"),
            new StoredRelation(hidden, behindHidden, "relatedTo"),
            new StoredRelation(root, visible, "relatedTo"),
            new StoredRelation(root, deleted, "relatedTo")));

    Map<String, Object> result = fixture.execute(root, Map.of("depth", 3));

    assertEquals(Set.of(root, visible), ids(result));
    assertEquals(2, edges(result).size());
    assertFalse(fixture.expanded.contains(hidden));
    assertFalse(fixture.expanded.contains(behindHidden));
    String serialized = JsonUtils.pojoToJson(result);
    assertFalse(serialized.contains(hidden.toString()));
    assertFalse(serialized.contains(deleted.toString()));
    assertFalse(serialized.contains("SecretName"));
    assertFalse(serialized.contains("hiddenCount"));
  }

  @Test
  void aDeniedRootFailsInsteadOfReturningAnEmptyGraph() {
    Fixture fixture = new Fixture();
    UUID root = fixture.term("SecretRoot");
    fixture.denied.add(root);

    assertThrows(AuthorizationException.class, () -> fixture.execute(root, Map.of()));
    assertTrue(fixture.expanded.isEmpty());
  }

  @Test
  void nodeLimitDoesNotLeaveDanglingEdges() {
    Fixture fixture = new Fixture();
    UUID root = fixture.term("Root");
    UUID a = fixture.term("A");
    UUID b = fixture.term("B");
    fixture.relations.add(new StoredRelation(root, a, "relatedTo"));
    fixture.relations.add(new StoredRelation(root, b, "relatedTo"));

    Map<String, Object> result = fixture.execute(root, Map.of("maxNodes", 2));

    assertEquals(Set.of(root, a), ids(result));
    assertEquals(2, edges(result).size());
    assertTrue((boolean) result.get("truncated"));
    assertFalse(JsonUtils.pojoToJson(result).contains(b.toString()));
  }

  @Test
  void relationInspectionBudgetBoundsEvenDeniedNeighbors() {
    Fixture fixture = new Fixture();
    UUID root = fixture.term("Root");
    for (int i = 0; i <= GetTermRelationGraphTool.MAX_INSPECTED_RELATIONS; i++) {
      UUID hidden = fixture.term("Hidden" + i);
      fixture.denied.add(hidden);
      fixture.relations.add(new StoredRelation(root, hidden, "relatedTo"));
    }

    Map<String, Object> result = fixture.execute(root, Map.of("depth", 3));

    assertEquals(Set.of(root), ids(result));
    assertTrue(edges(result).isEmpty());
    assertEquals(true, result.get("truncated"));
    assertEquals(GetTermRelationGraphTool.MAX_INSPECTED_RELATIONS + 1, fixture.readAttempts);
    assertEquals(GetTermRelationGraphTool.MAX_INSPECTED_RELATIONS + 1, fixture.largestQueryLimit);
  }

  @Test
  void denseGraphsRespectTheEdgeBudget() {
    Fixture fixture = new Fixture();
    List<UUID> nodes = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      nodes.add(fixture.term("Node" + i));
    }
    for (int i = 0; i < nodes.size(); i++) {
      for (int j = i + 1; j < nodes.size(); j++) {
        fixture.relations.add(new StoredRelation(nodes.get(i), nodes.get(j), "relatedTo"));
      }
    }

    Map<String, Object> result = fixture.execute(nodes.getFirst(), Map.of("depth", 2));

    assertEquals(GetTermRelationGraphTool.MAX_EDGES, edges(result).size());
    assertEquals(true, result.get("truncated"));
    Set<UUID> returned = ids(result);
    assertTrue(
        edges(result).stream()
            .allMatch(
                edge ->
                    returned.contains(UUID.fromString(edge.get("from")))
                        && returned.contains(UUID.fromString(edge.get("to")))));
  }

  @Test
  void supportsFqnRootAndSlimsReferenceMetadata() {
    Fixture fixture = new Fixture();
    UUID root = fixture.term("Root");
    fixture.terms.get(root).setDescription("Long private description");

    Map<String, Object> result =
        GetTermRelationGraphTool.execute(Map.of("fqn", "Glossary.Root"), fixture);

    assertEquals(Set.of(root), ids(result));
    assertEquals(root, ((EntityReference) result.get("root")).getId());
    assertFalse(JsonUtils.pojoToJson(result).contains("Long private description"));
  }

  @Test
  void validatesParametersBeforeReadingCatalogData() {
    Fixture fixture = new Fixture();
    UUID root = fixture.term("Root");
    for (Map<String, Object> params :
        List.of(
            Map.<String, Object>of("depth", 0),
            Map.<String, Object>of("depth", 4),
            Map.<String, Object>of("depth", 1.5),
            Map.<String, Object>of("maxNodes", 101),
            Map.<String, Object>of("maxNodes", 0),
            Map.<String, Object>of("direction", "sideways"),
            Map.<String, Object>of("relationTypes", List.of("bad type")),
            Map.<String, Object>of("fqn", "Glossary.Root"))) {
      assertThrows(IllegalArgumentException.class, () -> fixture.execute(root, params));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> GetTermRelationGraphTool.execute(Map.of(), fixture));
    assertEquals(0, fixture.readAttempts);
  }

  @Test
  void nullRelationTypeUsesBackwardCompatibleRelatedTo() {
    Fixture fixture = new Fixture();
    UUID a = fixture.term("A");
    UUID b = fixture.term("B");
    fixture.relations.add(new StoredRelation(a, b, null));

    assertEquals(
        Set.of(edge(a, b, "relatedTo"), edge(b, a, "relatedTo")),
        new HashSet<>(edges(fixture.execute(a, Map.of()))));
  }

  private static Map<String, String> edge(UUID from, UUID to, String type) {
    return Map.of("from", from.toString(), "to", to.toString(), "relationType", type);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, String>> edges(Map<String, Object> result) {
    return (List<Map<String, String>>) result.get("edges");
  }

  @SuppressWarnings("unchecked")
  private static Set<UUID> ids(Map<String, Object> result) {
    Set<UUID> ids = new HashSet<>();
    ((List<EntityReference>) result.get("nodes")).forEach(ref -> ids.add(ref.getId()));
    return ids;
  }

  private static class Fixture implements GraphAccess {
    final Map<UUID, EntityReference> terms = new HashMap<>();
    final List<StoredRelation> relations = new ArrayList<>();
    final Set<UUID> denied = new HashSet<>();
    final Set<UUID> expanded = new HashSet<>();
    int readAttempts;
    int largestQueryLimit;

    UUID term(String name) {
      UUID id = UUID.randomUUID();
      terms.put(
          id,
          new EntityReference()
              .withId(id)
              .withType("glossaryTerm")
              .withName(name)
              .withFullyQualifiedName("Glossary." + name));
      return id;
    }

    Map<String, Object> execute(UUID id, Map<String, Object> options) {
      Map<String, Object> params = new HashMap<>(options);
      params.put("termId", id.toString());
      return GetTermRelationGraphTool.execute(params, this);
    }

    @Override
    public EntityReference read(UUID id, String fqn) {
      readAttempts++;
      EntityReference ref =
          id == null
              ? terms.values().stream()
                  .filter(term -> term.getFullyQualifiedName().equals(fqn))
                  .findFirst()
                  .orElseThrow(() -> EntityNotFoundException.byName(fqn))
              : terms.get(id);
      if (denied.contains(id)) {
        throw new AuthorizationException("Not allowed");
      }
      if (ref == null) {
        throw EntityNotFoundException.byId(id.toString());
      }
      return ref;
    }

    @Override
    public List<StoredRelation> relations(UUID id, int limit) {
      assertFalse(denied.contains(id));
      assertTrue(expanded.add(id), "Each term must be expanded only once");
      largestQueryLimit = Math.max(largestQueryLimit, limit);
      return relations.stream()
          .filter(relation -> relation.from().equals(id) || relation.to().equals(id))
          .limit(limit)
          .toList();
    }

    @Override
    public String inverse(String relationType) {
      return Map.of("hasPart", "partOf", "partOf", "hasPart").get(relationType);
    }
  }
}
