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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openmetadata.mcp.util.McpResponseTrim;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.security.AuthorizationException;

class TermPersonaContextEnricherTest {
  private static final String TERM = "Manufacturing.UPH";
  private static final EntityReference PERSONA =
      new EntityReference()
          .withId(UUID.randomUUID())
          .withType("persona")
          .withName("uph-show")
          .withFullyQualifiedName("uph-show");

  @Test
  void leavesUnboundTermsAndTheirPagingUntouched() {
    var original = search(TERM);
    var catalog = new Catalog();

    assertThat(new TermPersonaContextEnricher(catalog).enrich("search_metadata", original))
        .isEqualTo(original);
    assertThat(catalog.reads).isZero();
  }

  @Test
  void embedsBoundContextWithoutChangingSearchPaging() {
    var original = search(TERM);
    var result = enriched(catalog("term"), original);
    var context = firstContext(result);

    assertThat(result.get("results")).isEqualTo(original.get("results"));
    assertThat(result.get("nextCursor")).isEqualTo("next-page");
    assertThat(context.get("status")).isEqualTo("loaded");
    assertThat(JsonUtils.pojoToJson(context))
        .contains("UPH display rules", "fingerprint-1", "\"source\":\"term\"");
  }

  @Test
  void loadsSharedPersonaOnceWhileKeepingEachBindingSource() {
    var catalog = catalog("user");
    catalog.bindings.put(
        "Manufacturing.OEE", new TermPersonaContextEnricher.Binding(PERSONA, "term"));
    var result = enriched(catalog, search(TERM, "Manufacturing.OEE", TERM));

    assertThat(contexts(result)).hasSize(1);
    assertThat((List<?>) firstContext(result).get("boundTerms")).hasSize(2);
    assertThat(catalog.reads).isEqualTo(1);
    assertThat(JsonUtils.pojoToJson(result)).contains("\"source\":\"user\"", "\"source\":\"term\"");
  }

  @Test
  void deniedPersonaIsUnavailableAndNeverFallsBack() {
    var catalog = catalog("user");
    catalog.failure = new AuthorizationException("private membership details");
    var result = enriched(catalog, search(TERM));

    assertThat(firstContext(result).get("status")).isEqualTo("unavailable");
    assertThat(JsonUtils.pojoToJson(result))
        .doesNotContain("UPH display rules", "private membership details", "nextCall");
    assertThat(catalog.reads).isEqualTo(1);
  }

  @Test
  void deletedOrDisabledPersonaLeavesTheOriginalQuerySuccessful() {
    var catalog = catalog("user");
    var original = search(TERM);
    for (RuntimeException failure :
        List.of(
            EntityNotFoundException.byMessage("missing persona"),
            new IllegalStateException("disabled"))) {
      catalog.failure = failure;
      var result = enriched(catalog, original);
      assertThat(result.get("results")).isEqualTo(original.get("results"));
      assertThat(firstContext(result).get("status")).isEqualTo("unavailable");
      assertThat(result).doesNotContainKey("error");
    }
  }

  @Test
  void unauthorizedTermDoesNotExposeItsBinding() {
    var catalog = catalog("user");
    catalog.hiddenTerm = TERM;
    var result = enriched(catalog, search(TERM));

    assertThat(JsonUtils.pojoToJson(result)).doesNotContain("uph-show", "UPH display rules");
    assertThat(catalog.reads).isZero();
  }

  @Test
  void fullFirstPartDefersInsteadOfTruncatingOriginalResults() {
    var catalog = catalog("term");
    catalog.page = page("\\\"你好".repeat(15_000), false);
    var original = new HashMap<>(search(TERM));
    original.put("description", "x".repeat(15_000));
    var result = enriched(catalog, original);

    assertThat(firstContext(result).get("status")).isEqualTo("deferred");
    assertThat(result.get("description")).isEqualTo(original.get("description"));
    assertThat(firstContext(result).get("nextCall"))
        .isEqualTo(
            Map.of(
                "tool",
                "get_persona_context",
                "arguments",
                Map.of("personaName", "uph-show", "format", "markdown", "part", 1)));
    assertThat(McpResponseTrim.serializedLength(result))
        .isLessThanOrEqualTo(McpResponseTrim.MAX_RESPONSE_CHARS);
  }

  @Test
  void loadedPartialDocumentHasExactContinuationAndFingerprint() {
    var catalog = catalog("term");
    catalog.page = page("first part", true);
    var context = firstContext(enriched(catalog, search(TERM)));

    assertThat(context.get("nextCall"))
        .isEqualTo(
            Map.of(
                "tool",
                "get_persona_context",
                "arguments",
                Map.of("personaName", "uph-show", "format", "markdown", "part", 2)));
    assertThat(JsonUtils.pojoToJson(context))
        .contains("\"hasMore\":true", "fingerprint-1", "\"totalParts\":2");
  }

  @Test
  void capsPersonaReadsAndReportsOmittedContexts() {
    var catalog = new Catalog();
    String[] terms =
        IntStream.range(0, 15).mapToObj(index -> "Term." + index).toArray(String[]::new);
    for (String term : terms) {
      catalog.bindings.put(
          term,
          new TermPersonaContextEnricher.Binding(
              new EntityReference()
                  .withId(UUID.randomUUID())
                  .withType("persona")
                  .withFullyQualifiedName(term),
              "term"));
    }
    var result = enriched(catalog, search(terms));

    assertThat(catalog.reads).isEqualTo(TermPersonaContextEnricher.MAX_PERSONAS);
    assertThat(result.get("personaContextsTruncated")).isEqualTo(true);
    assertThat(contexts(result)).hasSize(TermPersonaContextEnricher.MAX_PERSONAS);
  }

  @Test
  void almostFullOriginalResponseNeverGetsReplacedByBudgetEnvelope() {
    var original = new HashMap<>(search(TERM));
    original.put("description", "x".repeat(99_700));
    var result = enriched(catalog("term"), original);

    assertThat(result.get("description")).isEqualTo(original.get("description"));
    assertThat(McpResponseTrim.serializedLength(result))
        .isLessThanOrEqualTo(McpResponseTrim.MAX_RESPONSE_CHARS);
  }

  private static Catalog catalog(String source) {
    var catalog = new Catalog();
    catalog.bindings.put(TERM, new TermPersonaContextEnricher.Binding(PERSONA, source));
    return catalog;
  }

  private static Map<String, Object> search(String... names) {
    return Map.of(
        "results",
        List.of(names).stream()
            .map(name -> Map.of("entityType", "glossaryTerm", "fullyQualifiedName", name))
            .toList(),
        "nextCursor",
        "next-page",
        "hasMore",
        true);
  }

  private static Map<String, Object> enriched(Catalog catalog, Map<String, Object> result) {
    return new TermPersonaContextEnricher(catalog).enrich("search_metadata", result);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> contexts(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("personaContexts");
  }

  private static Map<String, Object> firstContext(Map<String, Object> result) {
    return contexts(result).getFirst();
  }

  private static PersonaContextReader.Page page(String content, boolean hasMore) {
    return new PersonaContextReader.Page(
        "markdown", content, 1, hasMore ? 2 : 1, hasMore, "fingerprint-1", "uph-show");
  }

  private static final class Catalog implements TermPersonaContextEnricher.Access {
    final Map<String, TermPersonaContextEnricher.Binding> bindings = new HashMap<>();
    int reads;
    String hiddenTerm;
    RuntimeException failure;
    PersonaContextReader.Page page = page("UPH display rules", false);

    @Override
    public Optional<TermPersonaContextEnricher.Binding> binding(McpTermReferences.Term term) {
      if (term.fullyQualifiedName().equals(hiddenTerm)) {
        throw new AuthorizationException("hidden term");
      }
      return Optional.ofNullable(bindings.get(term.fullyQualifiedName()));
    }

    @Override
    public PersonaContextReader.Page read(EntityReference persona) {
      reads++;
      if (failure != null) {
        throw failure;
      }
      return page;
    }
  }
}
