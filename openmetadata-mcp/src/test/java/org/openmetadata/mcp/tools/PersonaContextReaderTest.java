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
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.PersonaContextDefinition;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.DefaultAuthorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

class PersonaContextReaderTest {

  @Test
  void materializesTheSameCanonicalContextByNameAndBoundUuidWithoutACacheMock() {
    var security = new CatalogSecurityContext(() -> "member", "https", "Bearer", Set.of());
    var persona =
        new Persona()
            .withId(UUID.randomUUID())
            .withName("renamed-persona")
            .withFullyQualifiedName("renamed-persona")
            .withContextDefinition(
                new PersonaContextDefinition().withEnabled(true).withRules(List.of()));
    var staleReference =
        new EntityReference()
            .withId(persona.getId())
            .withType(Entity.PERSONA)
            .withFullyQualifiedName("old-persona-name");
    var user =
        new User()
            .withId(UUID.randomUUID())
            .withName("member")
            .withIsAdmin(false)
            .withIsBot(false)
            .withPersonas(List.of(staleReference));
    try (MockedStatic<Entity> entities = mockStatic(Entity.class);
        MockedStatic<DefaultAuthorizer> subjects = mockStatic(DefaultAuthorizer.class)) {
      subjects
          .when(() -> DefaultAuthorizer.getSubjectContext(security))
          .thenReturn(new SubjectContext(user, null));
      entities
          .when(
              () ->
                  Entity.getEntityByName(
                      Entity.PERSONA,
                      persona.getFullyQualifiedName(),
                      "contextDefinition,users",
                      Include.NON_DELETED))
          .thenReturn(persona);
      entities
          .when(
              () ->
                  Entity.getEntity(
                      Entity.PERSONA,
                      persona.getId(),
                      "contextDefinition,users",
                      Include.NON_DELETED))
          .thenReturn(persona);

      var reader = new PersonaContextReader(security);
      var json = reader.read(persona.getFullyQualifiedName(), "json", 1);
      var markdown = reader.read(staleReference);

      assertThat(
              JsonUtils.readTree(json.content())
                  .path("persona")
                  .path("fullyQualifiedName")
                  .asText())
          .isEqualTo("renamed-persona");
      assertThat(json.format()).isEqualTo("json");
      assertThat(markdown.format()).isEqualTo("markdown");
      assertThat(markdown.content()).contains("renamed-persona").doesNotContain("old-persona-name");
      assertThat(markdown.personaName()).isEqualTo("renamed-persona");
      assertThat(json.fingerprint()).isNotBlank().isEqualTo(markdown.fingerprint());
      assertThat(json.hasMore()).isFalse();
      assertThat(markdown.hasMore()).isFalse();
    }
  }

  @Test
  void pagesReassembleExactlyAndExposeCanonicalNameAndFingerprint() {
    String content = "first line\n".repeat(20_000);
    var first = PersonaContextReader.page(content, "markdown", 1, "hash", "renamed-persona");
    StringBuilder assembled = new StringBuilder();
    for (int part = 1; part <= first.totalParts(); part++) {
      var page = PersonaContextReader.page(content, "markdown", part, "hash", "renamed-persona");
      assembled.append(page.content());
      assertThat(page.hasMore()).isEqualTo(part < page.totalParts());
      assertThat(page.fingerprint()).isEqualTo("hash");
      assertThat(page.personaName()).isEqualTo("renamed-persona");
    }
    assertThat(assembled.toString()).isEqualTo(content);
  }

  @Test
  void doesNotSplitUnicodeSurrogatePairs() {
    String content = "\uD83D\uDE80".repeat(80_000);
    List<String> parts = PersonaContextReader.split(content);
    assertThat(String.join("", parts)).isEqualTo(content);
    for (String part : parts) {
      assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1))).isFalse();
      assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
    }
  }

  @Test
  void rejectsInvalidPartNumbers() {
    assertThatThrownBy(() -> PersonaContextReader.page("one", "markdown", 0, "hash", "persona"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PersonaContextReader.page("one", "markdown", 2, "hash", "persona"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDisabledContextBeforeMaterializingIt() {
    var persona =
        new Persona().withContextDefinition(new PersonaContextDefinition().withEnabled(false));
    assertThatThrownBy(() -> PersonaContextReader.requireEnabled(persona))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Persona context is disabled");
    PersonaContextReader.requireEnabled(new Persona());
    PersonaContextReader.requireEnabled(
        new Persona().withContextDefinition(new PersonaContextDefinition().withEnabled(true)));
  }

  @Test
  void deniesNonMemberBeforeReadingSharedCache() {
    var security = new CatalogSecurityContext(() -> "reader", "https", "Bearer", Set.of());
    var persona =
        new Persona()
            .withId(UUID.randomUUID())
            .withName("private")
            .withFullyQualifiedName("private");
    var subject =
        new SubjectContext(
            new User()
                .withId(UUID.randomUUID())
                .withName("reader")
                .withIsAdmin(false)
                .withIsBot(false),
            null);
    try (MockedStatic<Entity> entities = mockStatic(Entity.class);
        MockedStatic<DefaultAuthorizer> subjects = mockStatic(DefaultAuthorizer.class)) {
      entities
          .when(
              () ->
                  Entity.getEntityByName(
                      Entity.PERSONA, "private", "contextDefinition,users", Include.NON_DELETED))
          .thenReturn(persona);
      subjects.when(() -> DefaultAuthorizer.getSubjectContext(security)).thenReturn(subject);

      assertThatThrownBy(() -> new PersonaContextReader(security).read("private", "markdown", 1))
          .isInstanceOf(AuthorizationException.class);
    }
  }
}
