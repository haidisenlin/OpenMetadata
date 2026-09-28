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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpTermReferencesTest {
  private static final String TERM = "Manufacturing.UPH";

  @Test
  void semanticResultsUseTheEntityUuidInsteadOfTheChunkId() {
    UUID parentId = UUID.randomUUID();
    var hit =
        Map.of(
            "entityType",
            "glossaryTerm",
            "fullyQualifiedName",
            TERM,
            "id",
            UUID.randomUUID().toString(),
            "parentId",
            parentId.toString());

    assertThat(
            McpTermReferences.collect("semantic_search", Map.of("results", List.of(hit)))
                .terms()
                .getFirst()
                .id())
        .isEqualTo(parentId);
    assertThat(
            McpTermReferences.collect("search_metadata", Map.of("results", List.of(hit)))
                .terms()
                .getFirst()
                .id())
        .isNotEqualTo(parentId);
  }

  @Test
  void boundsRecordAndRelatedAssetTraversalIncludingEmptyReferences() {
    var bindings = IntStream.range(0, 600).mapToObj(index -> Map.of("term", Map.of())).toList();
    var assets =
        IntStream.range(0, 600).mapToObj(index -> Map.of("matchedTerms", List.of())).toList();

    assertThat(
            McpTermReferences.collect("resolve_record_binding", Map.of("bindings", bindings))
                .truncated())
        .isTrue();
    assertThat(
            McpTermReferences.collect("find_record_related_assets", Map.of("assets", assets))
                .truncated())
        .isTrue();
  }

  @Test
  void ignoresFailedResponsesAndToleratesOldMalformedIds() {
    assertThat(
            McpTermReferences.collect(
                    "search_metadata", Map.of("error", "denied", "results", List.of(term())))
                .terms())
        .isEmpty();
    assertThat(McpTermReferences.collect("get_entity_details", null).terms()).isEmpty();
    var malformed =
        Map.of("type", "glossaryTerm", "id", "old-invalid-id", "fullyQualifiedName", TERM);
    assertThat(McpTermReferences.collect("get_entity_details", malformed).terms().getFirst().id())
        .isNull();
    UUID id = UUID.randomUUID();
    var reference = Map.of("type", "glossaryTerm", "id", id.toString(), "fullyQualifiedName", TERM);
    assertThat(McpTermReferences.collect("get_entity_details", reference).terms().getFirst().id())
        .isEqualTo(id);
  }

  @ParameterizedTest
  @ValueSource(strings = {"search_metadata", "semantic_search"})
  void collectsOnlyExplicitTermsFromSearchPages(String tool) {
    var response =
        Map.of(
            "results",
            List.of(term(), Map.of("entityType", "table", "fullyQualifiedName", "orders")));

    assertThat(McpTermReferences.collect(tool, response).terms())
        .extracting(McpTermReferences.Term::fullyQualifiedName)
        .containsExactly(TERM);
  }

  @Test
  void doesNotInterpretDescriptionsTagsOrArbitraryNestedObjects() {
    var response =
        Map.of(
            "results",
            List.of(
                Map.of(
                    "entityType",
                    "table",
                    "fullyQualifiedName",
                    "orders",
                    "tags",
                    List.of(TERM),
                    "extension",
                    term(),
                    "description",
                    term().toString())));

    assertThat(McpTermReferences.collect("search_metadata", response).terms()).isEmpty();
    assertThat(McpTermReferences.collect("create_entity", term()).terms()).isEmpty();
  }

  @Test
  void collectsSuccessfulSingleAndBatchDetails() {
    assertThat(McpTermReferences.collect("get_entity_details", term()).terms()).hasSize(1);
    var failed =
        Map.of(
            "entityType", "glossaryTerm", "fullyQualifiedName", "Hidden.Term", "error", "denied");
    assertThat(
            McpTermReferences.collect(
                    "get_entity_details", Map.of("entities", List.of(term(), failed)))
                .terms())
        .extracting(McpTermReferences.Term::fullyQualifiedName)
        .containsExactly(TERM);
  }

  @Test
  void supportsStructuredContextMatchesInBothFormats() {
    var item = Map.of("type", "glossaryTerm", "fullyQualifiedName", TERM);
    assertThat(McpTermReferences.collect("find_context", Map.of("items", List.of(item))).terms())
        .hasSize(1);
    assertThat(
            McpTermReferences.collect(
                    "find_context",
                    Map.of(
                        "format",
                        "markdown",
                        "content",
                        "definition",
                        "matchedTerms",
                        List.of(item)))
                .terms())
        .hasSize(1);
  }

  @Test
  void collectsAndDeduplicatesGraphAndRecordBindingReferences() {
    var reference = Map.of("type", "glossaryTerm", "fullyQualifiedName", TERM);
    var graph = Map.of("root", reference, "nodes", List.of(reference));
    var binding = Map.of("term", reference);
    assertThat(McpTermReferences.collect("get_term_relation_graph", graph).terms()).hasSize(1);
    assertThat(
            McpTermReferences.collect(
                    "resolve_record_binding", Map.of("bindings", List.of(binding)))
                .terms())
        .hasSize(1);
    var related =
        Map.of(
            "bindings",
            List.of(binding),
            "graphs",
            List.of(graph),
            "assets",
            List.of(Map.of("matchedTerms", List.of(reference))));
    assertThat(McpTermReferences.collect("find_record_related_assets", related).terms()).hasSize(1);
  }

  @Test
  void boundsTheNumberOfTermsInspected() {
    var results =
        IntStream.range(0, 70)
            .mapToObj(
                index ->
                    Map.of("entityType", "glossaryTerm", "fullyQualifiedName", "Term." + index))
            .toList();
    var references = McpTermReferences.collect("search_metadata", Map.of("results", results));

    assertThat(references.terms()).hasSize(McpTermReferences.MAX_TERMS);
    assertThat(references.truncated()).isTrue();
  }

  private static Map<String, String> term() {
    return Map.of("entityType", "glossaryTerm", "fullyQualifiedName", TERM);
  }
}
