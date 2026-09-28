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
package org.openmetadata.service.aicontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.TermContextPersonaOverride;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;

class GlossaryTermPersonaBindingsTest {
  private static final UUID USER_ID = UUID.randomUUID();

  @Test
  void selectsTheAuthenticatedUsersOverrideBeforeTheTermDefault() {
    final EntityReference personal = reference(Entity.PERSONA);
    final GlossaryTerm term =
        new GlossaryTerm()
            .withContextPersona(reference(Entity.PERSONA))
            .withContextPersonaOverrides(List.of(override(USER_ID, personal)));

    final var selection = GlossaryTermPersonaBindings.select(term, USER_ID).orElseThrow();

    assertEquals(personal, selection.persona());
    assertEquals("user", selection.source());
  }

  @Test
  void anotherUsersOverrideDoesNotReplaceTheTermDefault() {
    final EntityReference shared = reference(Entity.PERSONA);
    final GlossaryTerm term =
        new GlossaryTerm()
            .withContextPersona(shared)
            .withContextPersonaOverrides(List.of(override(USER_ID, reference(Entity.PERSONA))));

    final var selection = GlossaryTermPersonaBindings.select(term, UUID.randomUUID()).orElseThrow();

    assertEquals(shared, selection.persona());
    assertEquals("term", selection.source());
  }

  @Test
  void anUnboundTermHasNoSelection() {
    assertTrue(GlossaryTermPersonaBindings.select(new GlossaryTerm(), USER_ID).isEmpty());
  }

  @Test
  void anotherUsersOverrideAloneHasNoSelection() {
    final GlossaryTerm term =
        new GlossaryTerm()
            .withContextPersonaOverrides(List.of(override(USER_ID, reference(Entity.PERSONA))));
    assertTrue(GlossaryTermPersonaBindings.select(term, UUID.randomUUID()).isEmpty());
  }

  @Test
  void normalizesReferencesFromTrustedEntityIds() {
    final EntityReference shared = reference(Entity.PERSONA).withName("spoofed");
    final EntityReference personal = reference(Entity.PERSONA).withName("spoofed");
    final GlossaryTerm term =
        new GlossaryTerm()
            .withContextPersona(shared)
            .withContextPersonaOverrides(List.of(override(USER_ID, personal)));

    GlossaryTermPersonaBindings.validateAndNormalize(
        term,
        (type, id) -> new EntityReference().withType(type).withId(id).withName("canonical-" + id));

    assertEquals("canonical-" + shared.getId(), term.getContextPersona().getName());
    final var normalized = term.getContextPersonaOverrides().getFirst();
    assertEquals("canonical-" + USER_ID, normalized.getUser().getName());
    assertEquals("canonical-" + personal.getId(), normalized.getPersona().getName());
  }

  @Test
  void acceptsAnUnboundTermWithoutResolvingEntities() {
    final GlossaryTerm term = new GlossaryTerm().withContextPersonaOverrides(null);
    GlossaryTermPersonaBindings.validateAndNormalize(
        term,
        (type, id) -> {
          throw new AssertionError("Unbound terms have no entity references to resolve");
        });
    assertTrue(term.getContextPersonaOverrides().isEmpty());
  }

  @Test
  void rejectsAUserRepeatedWithDifferentDisplayNames() {
    final var first = override(USER_ID, reference(Entity.PERSONA));
    final var second = override(USER_ID, reference(Entity.PERSONA));
    second.getUser().setName("another-name");
    final GlossaryTerm term =
        new GlossaryTerm().withContextPersonaOverrides(List.of(first, second));

    final var failure = assertThrows(IllegalArgumentException.class, () -> validate(term));

    assertTrue(failure.getMessage().contains("more than once"));
  }

  @Test
  void rejectsAnIncorrectDefaultPersonaReferenceType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> validate(new GlossaryTerm().withContextPersona(reference(Entity.USER))));
  }

  @Test
  void rejectsAReferenceWithoutAnId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validate(
                new GlossaryTerm()
                    .withContextPersona(new EntityReference().withType(Entity.PERSONA))));
  }

  @Test
  void rejectsAReferenceWithoutAType() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validate(
                new GlossaryTerm()
                    .withContextPersona(new EntityReference().withId(UUID.randomUUID()))));
  }

  @Test
  void rejectsAnOverrideWithoutAUser() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validate(
                new GlossaryTerm()
                    .withContextPersonaOverrides(
                        List.of(
                            new TermContextPersonaOverride()
                                .withPersona(reference(Entity.PERSONA))))));
  }

  @Test
  void rejectsAnOverrideWithoutAPersona() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validate(
                new GlossaryTerm().withContextPersonaOverrides(List.of(override(USER_ID, null)))));
  }

  @Test
  void rejectsANullOverrideEntry() {
    final List<TermContextPersonaOverride> overrides = new ArrayList<>();
    overrides.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> validate(new GlossaryTerm().withContextPersonaOverrides(overrides)));
  }

  @Test
  void rejectsMoreThanOneHundredOverrides() {
    final List<TermContextPersonaOverride> overrides = new ArrayList<>();
    for (int index = 0; index < 101; index++) {
      overrides.add(override(UUID.randomUUID(), reference(Entity.PERSONA)));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> validate(new GlossaryTerm().withContextPersonaOverrides(overrides)));
  }

  @Test
  void preservesMissingOrDeletedReferenceFailures() {
    final var failure = EntityNotFoundException.byMessage("Persona was deleted");
    final GlossaryTerm term = new GlossaryTerm().withContextPersona(reference(Entity.PERSONA));
    final var actual =
        assertThrows(
            EntityNotFoundException.class,
            () ->
                GlossaryTermPersonaBindings.validateAndNormalize(
                    term,
                    (type, id) -> {
                      throw failure;
                    }));
    assertSame(failure, actual);
  }

  @Test
  void selectingABindingDoesNotRequireOrChangeAUserDefault() {
    final EntityReference personal = reference(Entity.PERSONA);
    final GlossaryTerm term =
        new GlossaryTerm().withContextPersonaOverrides(List.of(override(USER_ID, personal)));
    assertEquals(
        personal, GlossaryTermPersonaBindings.select(term, USER_ID).orElseThrow().persona());
    assertFalse(GlossaryTermPersonaBindings.select(term, UUID.randomUUID()).isPresent());
  }

  @Test
  void unchangedBindingsDoNotResolveDeletedEntitiesDuringUnrelatedEdits() {
    final EntityReference deleted = reference(Entity.PERSONA);
    final GlossaryTerm original = new GlossaryTerm().withContextPersona(deleted);
    final GlossaryTerm updated =
        new GlossaryTerm().withContextPersona(deleted).withDescription("Updated definition");

    GlossaryTermPersonaBindings.validateChangedAndNormalize(
        original,
        updated,
        (type, id) -> {
          throw EntityNotFoundException.byMessage("Persona was deleted");
        });

    assertEquals(deleted, updated.getContextPersona());
  }

  @Test
  void changedBindingsStillRejectDeletedEntities() {
    final GlossaryTerm original = new GlossaryTerm();
    final GlossaryTerm updated = new GlossaryTerm().withContextPersona(reference(Entity.PERSONA));

    assertThrows(
        EntityNotFoundException.class,
        () ->
            GlossaryTermPersonaBindings.validateChangedAndNormalize(
                original,
                updated,
                (type, id) -> {
                  throw EntityNotFoundException.byMessage("Persona was deleted");
                }));
  }

  @Test
  void allowsExactlyOneHundredDistinctUserOverrides() {
    final List<TermContextPersonaOverride> overrides = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      overrides.add(override(UUID.randomUUID(), reference(Entity.PERSONA)));
    }
    final GlossaryTerm term = new GlossaryTerm().withContextPersonaOverrides(overrides);

    validate(term);

    assertEquals(100, term.getContextPersonaOverrides().size());
  }

  @Test
  void rejectsAPersonaUsedAsAnOverrideUser() {
    final TermContextPersonaOverride override =
        new TermContextPersonaOverride()
            .withUser(reference(Entity.PERSONA))
            .withPersona(reference(Entity.PERSONA));
    assertThrows(
        IllegalArgumentException.class,
        () -> validate(new GlossaryTerm().withContextPersonaOverrides(List.of(override))));
  }

  private static void validate(final GlossaryTerm term) {
    GlossaryTermPersonaBindings.validateAndNormalize(
        term, (type, id) -> new EntityReference().withType(type).withId(id));
  }

  private static EntityReference reference(final String type) {
    return new EntityReference().withType(type).withId(UUID.randomUUID());
  }

  private static TermContextPersonaOverride override(
      final UUID userId, final EntityReference persona) {
    return new TermContextPersonaOverride()
        .withUser(new EntityReference().withId(userId).withType(Entity.USER))
        .withPersona(persona);
  }
}
