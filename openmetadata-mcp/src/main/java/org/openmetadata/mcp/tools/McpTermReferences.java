/*
 * Copyright 2026 Collate
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openmetadata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;

/** Extracts explicitly typed term references from the documented result shapes of read tools. */
@Slf4j
final class McpTermReferences {
  static final int MAX_TERMS = 50;
  private static final int MAX_INSPECTED = 500;
  private static final String ERROR = "error";
  private final Map<String, Term> terms = new LinkedHashMap<>();
  private boolean truncated;
  private int inspected;

  record Term(UUID id, String fullyQualifiedName) {}

  record Matches(List<Term> terms, boolean truncated) {
    Matches {
      terms = List.copyOf(terms);
    }
  }

  private McpTermReferences() {}

  static Matches collect(final String toolName, final Object result) {
    final McpTermReferences references = new McpTermReferences();
    final JsonNode response = JsonUtils.getObjectMapper().valueToTree(result);
    if (response != null && response.isObject() && !response.hasNonNull(ERROR)) {
      references.collectResult(toolName, response);
    }
    return new Matches(List.copyOf(references.terms.values()), references.truncated);
  }

  private void collectResult(final String toolName, final JsonNode response) {
    switch (toolName) {
      case "search_metadata" -> addAll(response.path("results"));
      case "semantic_search" -> addAll(response.path("results"), true);
      case "get_entity_details" -> {
        add(response);
        addAll(response.path("entities"));
      }
      case "find_context" -> {
        addAll(response.path("items"));
        addAll(response.path("matchedTerms"));
      }
      case "get_term_relation_graph" -> addGraph(response);
      case "resolve_record_binding" -> addBindings(response.path("bindings"));
      case "find_record_related_assets" -> addRelatedAssets(response);
      default -> {
        // Mutation results and arbitrary nested metadata do not select query context.
      }
    }
  }

  private void addGraph(final JsonNode graph) {
    add(graph.path("root"));
    addAll(graph.path("nodes"));
  }

  private void addRelatedAssets(final JsonNode response) {
    addBindings(response.path("bindings"));
    for (JsonNode graph : response.path("graphs")) {
      if (!canInspect()) {
        break;
      }
      addGraph(graph);
    }
    for (JsonNode asset : response.path("assets")) {
      if (!canInspect()) {
        break;
      }
      inspected++;
      addAll(asset.path("matchedTerms"));
    }
  }

  private void addBindings(final JsonNode bindings) {
    for (JsonNode binding : bindings) {
      if (!canInspect()) {
        break;
      }
      add(binding.path("term"));
    }
  }

  private void addAll(final JsonNode candidates) {
    addAll(candidates, false);
  }

  private void addAll(final JsonNode candidates, final boolean semantic) {
    for (JsonNode candidate : candidates) {
      if (!canInspect()) {
        break;
      }
      add(candidate, semantic);
    }
  }

  private void add(final JsonNode candidate) {
    add(candidate, false);
  }

  private boolean canInspect() {
    final boolean allowed = inspected < MAX_INSPECTED;
    truncated |= !allowed;
    return allowed;
  }

  private void add(final JsonNode candidate, final boolean semantic) {
    inspected++;
    final String type = candidate.path("entityType").asText(candidate.path("type").asText());
    if (Entity.GLOSSARY_TERM.equals(type) && !candidate.hasNonNull(ERROR)) {
      final String fqn = candidate.path("fullyQualifiedName").asText(null);
      if (fqn != null && !fqn.isBlank()) {
        addTerm(new Term(uuid(candidate.path(semantic ? "parentId" : "id").asText(null)), fqn));
      }
    }
  }

  private void addTerm(final Term term) {
    final String key = term.id() == null ? term.fullyQualifiedName() : term.id().toString();
    if (terms.size() < MAX_TERMS || terms.containsKey(key)) {
      terms.putIfAbsent(key, term);
    } else {
      truncated = true;
    }
  }

  private static UUID uuid(final String value) {
    UUID id = null;
    if (value != null) {
      try {
        id = UUID.fromString(value);
      } catch (IllegalArgumentException exception) {
        LOG.debug("Ignoring malformed term id; binding will be reloaded by FQN");
      }
    }
    return id;
  }
}
