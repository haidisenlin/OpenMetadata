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

import static org.openmetadata.schema.type.MetadataOperation.VIEW_BASIC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.mcp.util.McpResponseTrim;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.GlossaryTermPersonaBindings;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.OperationContext;
import org.openmetadata.service.security.policyevaluator.ResourceContext;

/** Adds caller-specific, term-bound context without changing entity results or the active Persona. */
@Slf4j
final class TermPersonaContextEnricher {
  static final int MAX_PERSONAS = 10;
  private static final String CONTEXTS = "personaContexts";
  private static final String TRUNCATED = "personaContextsTruncated";
  private static final String STATUS = "status";
  private static final String LOADED = "loaded";
  private static final String DEFERRED = "deferred";
  private static final String UNAVAILABLE = "unavailable";
  private static final String NEXT_CALL = "nextCall";
  private static final String CONTEXT = "context";
  private static final String PERSONA = "persona";
  private static final String BOUND_TERMS = "boundTerms";
  private static final String FQN = "fullyQualifiedName";
  private final Access access;

  record Binding(EntityReference persona, String source, String termFqn) {
    Binding(EntityReference persona, String source) {
      this(persona, source, null);
    }
  }

  interface Access {
    Optional<Binding> binding(McpTermReferences.Term term);

    PersonaContextReader.Page read(EntityReference persona);
  }

  private record Group(EntityReference persona, List<Map<String, String>> terms) {}

  TermPersonaContextEnricher(final Access access) {
    this.access = access;
  }

  static Object enrich(
      final String toolName,
      final Object result,
      final Authorizer authorizer,
      final CatalogSecurityContext securityContext) {
    Object enriched = result;
    if (result instanceof Map<?, ?> map) {
      final Map<String, Object> response = new LinkedHashMap<>();
      map.forEach((key, value) -> response.put(String.valueOf(key), value));
      enriched =
          new TermPersonaContextEnricher(new CatalogAccess(authorizer, securityContext))
              .enrich(toolName, response);
    }
    return enriched;
  }

  Map<String, Object> enrich(final String toolName, final Map<String, Object> original) {
    final McpTermReferences.Matches matches = McpTermReferences.collect(toolName, original);
    final List<Group> groups = groups(matches.terms());
    final Map<String, Object> response = new LinkedHashMap<>(original);
    final boolean truncated = matches.truncated() || groups.size() > MAX_PERSONAS;
    final List<Group> selected = groups.subList(0, Math.min(groups.size(), MAX_PERSONAS));
    final List<Map<String, Object>> contexts = prepareEnvelope(response, selected, truncated);
    for (int index = 0; index < contexts.size(); index++) {
      if (!load(response, contexts, index, selected.get(index))) {
        contexts.subList(index, contexts.size()).clear();
        response.put(TRUNCATED, true);
        fitSummaries(response, contexts);
        break;
      }
    }
    return response;
  }

  private List<Group> groups(final List<McpTermReferences.Term> terms) {
    final Map<String, Group> groups = new LinkedHashMap<>();
    for (McpTermReferences.Term term : terms) {
      try {
        access.binding(term).ifPresent(binding -> addBinding(groups, term, binding));
      } catch (RuntimeException exception) {
        LOG.debug("Term context binding unavailable: {}", exception.getClass().getSimpleName());
      }
    }
    return List.copyOf(groups.values());
  }

  private static void addBinding(
      final Map<String, Group> groups, final McpTermReferences.Term term, final Binding binding) {
    final EntityReference persona = binding.persona();
    final String key =
        persona.getId() == null ? persona.getFullyQualifiedName() : persona.getId().toString();
    final Group group =
        groups.computeIfAbsent(key, ignored -> new Group(persona, new ArrayList<>()));
    final String termName =
        binding.termFqn() == null ? term.fullyQualifiedName() : binding.termFqn();
    group.terms().add(Map.of(FQN, termName, "source", binding.source()));
  }

  private static List<Map<String, Object>> prepareEnvelope(
      final Map<String, Object> response, final List<Group> groups, final boolean truncated) {
    final List<Map<String, Object>> contexts = new ArrayList<>();
    for (Group group : groups) {
      final Map<String, Object> summary = summary(group);
      if (group.persona().getFullyQualifiedName() != null) {
        summary.put(NEXT_CALL, nextCall(group.persona().getFullyQualifiedName(), 1));
      }
      contexts.add(summary);
    }
    if (!contexts.isEmpty()) {
      response.put(CONTEXTS, contexts);
    }
    if (truncated) {
      response.put(TRUNCATED, true);
    }
    fitSummaries(response, contexts);
    return contexts;
  }

  private static void fitSummaries(
      final Map<String, Object> response, final List<Map<String, Object>> contexts) {
    while (!fits(response) && !contexts.isEmpty()) {
      contexts.removeLast();
      response.put(TRUNCATED, true);
    }
    if (contexts.isEmpty()) {
      response.remove(CONTEXTS);
    }
    if (!fits(response)) {
      response.remove(TRUNCATED);
    }
  }

  private static Map<String, Object> summary(final Group group) {
    final Map<String, Object> summary = new LinkedHashMap<>();
    summary.put(PERSONA, compactPersona(group.persona()));
    summary.put(BOUND_TERMS, List.copyOf(group.terms()));
    summary.put(STATUS, UNAVAILABLE);
    return summary;
  }

  private boolean load(
      final Map<String, Object> response,
      final List<Map<String, Object>> contexts,
      final int index,
      final Group group) {
    boolean withinBudget = true;
    try {
      final PersonaContextReader.Page page = access.read(group.persona());
      final Map<String, Object> loaded = loaded(group, page);
      contexts.set(index, loaded);
      if (!fits(response)) {
        contexts.set(index, deferred(group, page));
      }
      if (!fits(response)) {
        withinBudget = false;
      }
    } catch (RuntimeException exception) {
      contexts.set(index, summary(group));
      LOG.debug("Bound Persona context unavailable: {}", exception.getClass().getSimpleName());
    }
    return withinBudget;
  }

  private static Map<String, Object> loaded(
      final Group group, final PersonaContextReader.Page page) {
    final Map<String, Object> result = summary(group);
    result.put(STATUS, LOADED);
    result.put(CONTEXT, JsonUtils.getMap(page));
    if (page.hasMore()) {
      result.put(NEXT_CALL, nextCall(page.personaName(), page.part() + 1));
    }
    return result;
  }

  private static Map<String, Object> deferred(
      final Group group, final PersonaContextReader.Page page) {
    final Map<String, Object> result = summary(group);
    result.put(STATUS, DEFERRED);
    result.put(NEXT_CALL, nextCall(page.personaName(), 1));
    return result;
  }

  private static Map<String, Object> nextCall(final String personaName, final int part) {
    return Map.of(
        "tool",
        "get_persona_context",
        "arguments",
        Map.of("personaName", personaName, "format", "markdown", "part", part));
  }

  private static Map<String, Object> compactPersona(final EntityReference persona) {
    final Map<String, Object> reference = new LinkedHashMap<>();
    reference.put("id", persona.getId());
    reference.put("type", Entity.PERSONA);
    reference.put(FQN, persona.getFullyQualifiedName());
    return reference;
  }

  private static boolean fits(final Map<String, Object> response) {
    return McpResponseTrim.serializedLength(response) <= McpResponseTrim.MAX_RESPONSE_CHARS;
  }

  static final class CatalogAccess implements Access {
    private static final String BINDING_FIELDS = "contextPersona,contextPersonaOverrides";
    private final Authorizer authorizer;
    private final CatalogSecurityContext securityContext;

    CatalogAccess(final Authorizer authorizer, final CatalogSecurityContext securityContext) {
      this.authorizer = authorizer;
      this.securityContext = securityContext;
    }

    @Override
    public Optional<Binding> binding(final McpTermReferences.Term reference) {
      authorizer.authorize(
          securityContext,
          new OperationContext(Entity.GLOSSARY_TERM, VIEW_BASIC),
          new ResourceContext<>(
              Entity.GLOSSARY_TERM,
              reference.id(),
              reference.id() == null ? reference.fullyQualifiedName() : null));
      final GlossaryTerm term =
          reference.id() == null
              ? Entity.getEntityByName(
                  Entity.GLOSSARY_TERM,
                  reference.fullyQualifiedName(),
                  BINDING_FIELDS,
                  Include.NON_DELETED)
              : Entity.getEntity(
                  Entity.GLOSSARY_TERM, reference.id(), BINDING_FIELDS, Include.NON_DELETED);
      final UUID userId = DefaultAuthorizer.getSubjectContext(securityContext).user().getId();
      return GlossaryTermPersonaBindings.select(term, userId)
          .map(
              selection ->
                  new Binding(
                      selection.persona(), selection.source(), term.getFullyQualifiedName()));
    }

    @Override
    public PersonaContextReader.Page read(final EntityReference persona) {
      return new PersonaContextReader(securityContext).read(persona);
    }
  }
}
