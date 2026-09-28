/*
 * Copyright 2026 Collate.
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
package org.openmetadata.it.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.it.util.NamespaceCleanup;
import org.openmetadata.it.util.SdkClients;
import org.openmetadata.it.util.TestNamespace;
import org.openmetadata.it.util.TestNamespaceExtension;
import org.openmetadata.schema.api.data.CreateGlossary;
import org.openmetadata.schema.api.data.CreateGlossaryTerm;
import org.openmetadata.schema.api.teams.CreatePersona;
import org.openmetadata.schema.api.teams.CreateUser;
import org.openmetadata.schema.entity.data.Glossary;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.TermContextPersonaOverride;
import org.openmetadata.sdk.exceptions.OpenMetadataException;
import org.openmetadata.sdk.network.HttpMethod;
import org.openmetadata.service.Entity;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(TestNamespaceExtension.class)
class GlossaryTermPersonaBindingsIT {
  private static final String BINDING_FIELDS = "contextPersona,contextPersonaOverrides";

  @AfterEach
  void cleanup(final TestNamespace namespace) {
    NamespaceCleanup.deleteRoots(namespace.drainTrackedRoots());
  }

  @Test
  void storesCanonicalDefaultAndUserBindings(final TestNamespace namespace) {
    final Persona shared = createPersona(namespace, "shared");
    final Persona personal = createPersona(namespace, "personal");
    final User user = createUser(namespace);
    final CreateGlossaryTerm request =
        request(namespace)
            .withContextPersona(shared.getEntityReference().withName("untrusted-name"))
            .withContextPersonaOverrides(List.of(override(user, personal)));

    final GlossaryTerm created = SdkClients.adminClient().glossaryTerms().create(request);
    final GlossaryTerm fetched = read(created);

    assertEquals(shared.getId(), fetched.getContextPersona().getId());
    assertEquals(shared.getName(), fetched.getContextPersona().getName());
    assertEquals(user.getId(), fetched.getContextPersonaOverrides().getFirst().getUser().getId());
    assertEquals(
        personal.getId(), fetched.getContextPersonaOverrides().getFirst().getPersona().getId());
  }

  @Test
  void patchesBindingsWithVersionHistory(final TestNamespace namespace) {
    final Persona original = createPersona(namespace, "original");
    final Persona replacement = createPersona(namespace, "replacement");
    final GlossaryTerm created =
        SdkClients.adminClient()
            .glossaryTerms()
            .create(request(namespace).withContextPersona(original.getEntityReference()));
    final GlossaryTerm fetched = read(created);

    final GlossaryTerm updated =
        SdkClients.adminClient()
            .glossaryTerms()
            .update(fetched.getId(), fetched.withContextPersona(replacement.getEntityReference()));

    assertTrue(updated.getVersion() > created.getVersion());
    assertEquals(replacement.getId(), read(updated).getContextPersona().getId());
    final GlossaryTerm historical =
        SdkClients.adminClient()
            .glossaryTerms()
            .getVersion(created.getId().toString(), created.getVersion());
    assertEquals(original.getId(), historical.getContextPersona().getId());
    assertTrue(
        updated.getChangeDescription().getFieldsUpdated().stream()
            .anyMatch(field -> field.getName().equals("contextPersona")));
  }

  @Test
  void removesDefaultAndOverridesThroughPatch(final TestNamespace namespace) {
    final Persona persona = createPersona(namespace, "removable");
    final User user = createUser(namespace);
    final GlossaryTerm created =
        SdkClients.adminClient()
            .glossaryTerms()
            .create(
                request(namespace)
                    .withContextPersona(persona.getEntityReference())
                    .withContextPersonaOverrides(List.of(override(user, persona))));
    final GlossaryTerm fetched = read(created);

    SdkClients.adminClient()
        .glossaryTerms()
        .update(
            fetched.getId(),
            fetched.withContextPersona(null).withContextPersonaOverrides(List.of()));

    final GlossaryTerm cleared = read(created);
    assertNull(cleared.getContextPersona());
    assertTrue(cleared.getContextPersonaOverrides().isEmpty());
    assertTrue(cleared.getVersion() > created.getVersion());
  }

  @Test
  void updatesBindingsThroughPut(final TestNamespace namespace) {
    final Persona original = createPersona(namespace, "put-original");
    final Persona replacement = createPersona(namespace, "put-replacement");
    final CreateGlossaryTerm request =
        request(namespace).withContextPersona(original.getEntityReference());
    final GlossaryTerm created = SdkClients.adminClient().glossaryTerms().create(request);

    final GlossaryTerm updated =
        SdkClients.adminClient()
            .getHttpClient()
            .execute(
                HttpMethod.PUT,
                "/v1/glossaryTerms",
                request.withContextPersona(replacement.getEntityReference()),
                GlossaryTerm.class);

    assertEquals(created.getId(), updated.getId());
    assertEquals(replacement.getId(), read(updated).getContextPersona().getId());
  }

  @Test
  void rejectsDuplicateUsers(final TestNamespace namespace) {
    final Persona first = createPersona(namespace, "first");
    final Persona second = createPersona(namespace, "second");
    final User user = createUser(namespace);
    final CreateGlossaryTerm request =
        request(namespace)
            .withContextPersonaOverrides(List.of(override(user, first), override(user, second)));

    final OpenMetadataException failure =
        assertThrows(
            OpenMetadataException.class,
            () -> SdkClients.adminClient().glossaryTerms().create(request));

    assertEquals(400, failure.getStatusCode());
    assertTrue(failure.getMessage().contains("more than once"));
  }

  @Test
  void rejectsWrongReferenceTypes(final TestNamespace namespace) {
    final User user = createUser(namespace);
    final CreateGlossaryTerm request =
        request(namespace).withContextPersona(user.getEntityReference());

    final OpenMetadataException failure =
        assertThrows(
            OpenMetadataException.class,
            () -> SdkClients.adminClient().glossaryTerms().create(request));

    assertEquals(400, failure.getStatusCode());
  }

  @Test
  void rejectsUnknownPersonaIds(final TestNamespace namespace) {
    final CreateGlossaryTerm request =
        request(namespace)
            .withContextPersona(
                new EntityReference().withType(Entity.PERSONA).withId(UUID.randomUUID()));

    final OpenMetadataException failure =
        assertThrows(
            OpenMetadataException.class,
            () -> SdkClients.adminClient().glossaryTerms().create(request));

    assertEquals(404, failure.getStatusCode());
  }

  @Test
  void leavesADeletedPersonaBindingExplicitAndAllowsClearingIt(final TestNamespace namespace) {
    final Persona persona = createPersona(namespace, "deleted");
    final GlossaryTerm created =
        SdkClients.adminClient()
            .glossaryTerms()
            .create(request(namespace).withContextPersona(persona.getEntityReference()));
    SdkClients.adminClient().personas().delete(persona.getId());
    final GlossaryTerm fetched = read(created);

    assertEquals(persona.getId(), fetched.getContextPersona().getId());
    SdkClients.adminClient()
        .glossaryTerms()
        .update(fetched.getId(), fetched.withDescription("Updated after Persona deletion"));
    final GlossaryTerm edited = read(created);
    assertEquals("Updated after Persona deletion", edited.getDescription());
    SdkClients.adminClient()
        .glossaryTerms()
        .update(edited.getId(), edited.withContextPersona(null));
    assertNull(read(created).getContextPersona());
  }

  @Test
  void aReaderCannotModifyBindings(final TestNamespace namespace) {
    final Persona persona = createPersona(namespace, "restricted");
    final GlossaryTerm term = SdkClients.adminClient().glossaryTerms().create(request(namespace));
    final GlossaryTerm fetched =
        SdkClients.dataConsumerClient()
            .glossaryTerms()
            .get(term.getId().toString(), BINDING_FIELDS);

    final OpenMetadataException failure =
        assertThrows(
            OpenMetadataException.class,
            () ->
                SdkClients.dataConsumerClient()
                    .glossaryTerms()
                    .update(
                        fetched.getId(), fetched.withContextPersona(persona.getEntityReference())));

    assertEquals(403, failure.getStatusCode());
    assertNull(read(term).getContextPersona());
  }

  @Test
  void bindingDoesNotAssignThePersonaOrChangeTheUsersDefault(final TestNamespace namespace) {
    final Persona persona = createPersona(namespace, "selection-only");
    final User user = createUser(namespace);
    final User before =
        SdkClients.adminClient().users().get(user.getId().toString(), "personas,defaultPersona");

    SdkClients.adminClient()
        .glossaryTerms()
        .create(request(namespace).withContextPersonaOverrides(List.of(override(user, persona))));

    final User after =
        SdkClients.adminClient().users().get(user.getId().toString(), "personas,defaultPersona");
    assertEquals(before.getPersonas(), after.getPersonas());
    assertEquals(before.getDefaultPersona(), after.getDefaultPersona());
  }

  private GlossaryTerm read(final GlossaryTerm term) {
    return SdkClients.adminClient().glossaryTerms().get(term.getId().toString(), BINDING_FIELDS);
  }

  private CreateGlossaryTerm request(final TestNamespace namespace) {
    final Glossary glossary =
        namespace.trackRoot(
            Entity.GLOSSARY,
            SdkClients.adminClient()
                .glossaries()
                .create(new CreateGlossary().withName(namespace.shortPrefix("context-glossary"))));
    return new CreateGlossaryTerm()
        .withName("UPH")
        .withDescription("Units per hour")
        .withGlossary(glossary.getFullyQualifiedName());
  }

  private Persona createPersona(final TestNamespace namespace, final String suffix) {
    return namespace.trackRoot(
        Entity.PERSONA,
        SdkClients.adminClient()
            .personas()
            .create(new CreatePersona().withName(namespace.shortPrefix("context-" + suffix))));
  }

  private User createUser(final TestNamespace namespace) {
    final String name = namespace.shortPrefix("context-user");
    return namespace.trackRoot(
        Entity.USER,
        SdkClients.adminClient()
            .users()
            .create(new CreateUser().withName(name).withEmail(name + "@example.com")));
  }

  private TermContextPersonaOverride override(final User user, final Persona persona) {
    return new TermContextPersonaOverride()
        .withUser(user.getEntityReference())
        .withPersona(persona.getEntityReference());
  }
}
