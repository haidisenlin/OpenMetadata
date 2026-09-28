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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.type.TermContextPersonaOverride;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

class TermPersonaCatalogAccessTest {
  private static final String FIELDS = "contextPersona,contextPersonaOverrides";
  private static final String OLD_FQN = "Manufacturing.UPH";
  private static final CatalogSecurityContext SECURITY =
      new CatalogSecurityContext(() -> "reader", "https", "Bearer", Set.of());
  private static final User USER = new User().withId(UUID.randomUUID()).withName("reader");

  @Test
  void centralizedEnrichmentKeepsAuthorizedQueryWhenBoundPersonaIsDisabled() {
    UUID termId = UUID.randomUUID();
    var persona =
        new Persona()
            .withId(UUID.randomUUID())
            .withName("disabled-persona")
            .withFullyQualifiedName("disabled-persona")
            .withContextDefinition(new PersonaContextDefinition().withEnabled(false));
    var term =
        new GlossaryTerm()
            .withId(termId)
            .withFullyQualifiedName(OLD_FQN)
            .withContextPersona(persona.getEntityReference());
    var result =
        Map.of(
            "entityType",
            Entity.GLOSSARY_TERM,
            "id",
            termId.toString(),
            "fullyQualifiedName",
            OLD_FQN);
    var subject =
        new SubjectContext(
            new User().withId(UUID.randomUUID()).withName("admin").withIsAdmin(true), null);
    try (MockedStatic<Entity> catalog = mockStatic(Entity.class);
        MockedStatic<DefaultAuthorizer> subjects = mockStatic(DefaultAuthorizer.class)) {
      subjects.when(() -> DefaultAuthorizer.getSubjectContext(SECURITY)).thenReturn(subject);
      catalog
          .when(() -> Entity.getEntity(Entity.GLOSSARY_TERM, termId, FIELDS, Include.NON_DELETED))
          .thenReturn(term);
      catalog
          .when(
              () ->
                  Entity.getEntity(
                      Entity.PERSONA,
                      persona.getId(),
                      "contextDefinition,users",
                      Include.NON_DELETED))
          .thenReturn(persona);

      Object enriched =
          TermPersonaContextEnricher.enrich(
              "get_entity_details", result, new DefaultAuthorizer(), SECURITY);
      var document = JsonUtils.getObjectMapper().valueToTree(enriched);

      assertThat(document.path("fullyQualifiedName").asText()).isEqualTo(OLD_FQN);
      assertThat(document.path("personaContexts").get(0).path("status").asText())
          .isEqualTo("unavailable");
      assertThat(document.has("error")).isFalse();
      assertThat(document.path("personaContexts").get(0).has("nextCall")).isFalse();
    }
  }

  @Test
  void staleSearchFqnCannotSelectAnotherTermsPersona() {
    UUID termId = UUID.randomUUID();
    var original =
        new GlossaryTerm()
            .withId(termId)
            .withFullyQualifiedName("Manufacturing.RenamedUPH")
            .withContextPersona(persona("correct"));
    var replacement =
        new GlossaryTerm()
            .withId(UUID.randomUUID())
            .withFullyQualifiedName(OLD_FQN)
            .withContextPersona(persona("wrong"));
    try (MockedStatic<Entity> catalog = mockStatic(Entity.class);
        MockedStatic<DefaultAuthorizer> subjects = mockStatic(DefaultAuthorizer.class)) {
      subjects
          .when(() -> DefaultAuthorizer.getSubjectContext(SECURITY))
          .thenReturn(new SubjectContext(USER, null));
      catalog
          .when(() -> Entity.getEntity(Entity.GLOSSARY_TERM, termId, FIELDS, Include.NON_DELETED))
          .thenReturn(original);
      catalog
          .when(
              () ->
                  Entity.getEntityByName(
                      Entity.GLOSSARY_TERM, OLD_FQN, FIELDS, Include.NON_DELETED))
          .thenReturn(replacement);

      var binding = access().binding(new McpTermReferences.Term(termId, OLD_FQN)).orElseThrow();

      assertThat(binding.persona().getFullyQualifiedName()).isEqualTo("correct");
      assertThat(binding.termFqn()).isEqualTo("Manufacturing.RenamedUPH");
    }
  }

  @Test
  void deletedTermIdDoesNotFallBackToItsReusedName() {
    UUID deletedId = UUID.randomUUID();
    var replacement =
        new GlossaryTerm().withFullyQualifiedName(OLD_FQN).withContextPersona(persona("wrong"));
    try (MockedStatic<Entity> catalog = mockStatic(Entity.class)) {
      catalog
          .when(
              () -> Entity.getEntity(Entity.GLOSSARY_TERM, deletedId, FIELDS, Include.NON_DELETED))
          .thenThrow(EntityNotFoundException.byMessage("deleted term"));
      catalog
          .when(
              () ->
                  Entity.getEntityByName(
                      Entity.GLOSSARY_TERM, OLD_FQN, FIELDS, Include.NON_DELETED))
          .thenReturn(replacement);

      assertThatThrownBy(() -> access().binding(new McpTermReferences.Term(deletedId, OLD_FQN)))
          .isInstanceOf(EntityNotFoundException.class);
    }
  }

  @Test
  void resolvesOverridesUsingAuthenticatedUserUuidWhenOnlyFqnIsAvailable() {
    var override =
        new TermContextPersonaOverride()
            .withUser(new EntityReference().withType(Entity.USER).withId(USER.getId()))
            .withPersona(persona("for-reader"));
    var term =
        new GlossaryTerm()
            .withFullyQualifiedName(OLD_FQN)
            .withContextPersona(persona("default"))
            .withContextPersonaOverrides(List.of(override));
    try (MockedStatic<Entity> catalog = mockStatic(Entity.class);
        MockedStatic<DefaultAuthorizer> subjects = mockStatic(DefaultAuthorizer.class)) {
      subjects
          .when(() -> DefaultAuthorizer.getSubjectContext(SECURITY))
          .thenReturn(new SubjectContext(USER, null));
      catalog
          .when(
              () ->
                  Entity.getEntityByName(
                      Entity.GLOSSARY_TERM, OLD_FQN, FIELDS, Include.NON_DELETED))
          .thenReturn(term);

      var binding = access().binding(new McpTermReferences.Term(null, OLD_FQN)).orElseThrow();

      assertThat(binding.persona().getFullyQualifiedName()).isEqualTo("for-reader");
      assertThat(binding.source()).isEqualTo("user");
    }
  }

  private static TermPersonaContextEnricher.CatalogAccess access() {
    return new TermPersonaContextEnricher.CatalogAccess(mock(Authorizer.class), SECURITY);
  }

  private static EntityReference persona(String name) {
    return new EntityReference()
        .withId(UUID.randomUUID())
        .withType(Entity.PERSONA)
        .withFullyQualifiedName(name);
  }
}
