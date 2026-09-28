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

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.TermContextPersonaOverride;
import org.openmetadata.service.Entity;

/** Selects term context without changing user preferences or granting access to a Persona. */
public final class GlossaryTermPersonaBindings {
  public static final String CONTEXT_PERSONA = "contextPersona";
  public static final String CONTEXT_PERSONA_OVERRIDES = "contextPersonaOverrides";
  public static final String USER_SOURCE = "user";
  public static final String TERM_SOURCE = "term";
  private static final int MAX_USER_OVERRIDES = 100;

  private GlossaryTermPersonaBindings() {}

  public record Selection(EntityReference persona, String source) {}

  public static Optional<Selection> select(final GlossaryTerm term, final UUID userId) {
    final Optional<Selection> userSelection =
        listOrEmpty(term.getContextPersonaOverrides()).stream()
            .filter(binding -> binding.getUser().getId().equals(userId))
            .findFirst()
            .map(binding -> new Selection(binding.getPersona(), USER_SOURCE));
    return userSelection.or(
        () ->
            Optional.ofNullable(term.getContextPersona())
                .map(persona -> new Selection(persona, TERM_SOURCE)));
  }

  public static void validateAndNormalize(
      final GlossaryTerm term, final BiFunction<String, UUID, EntityReference> resolver) {
    final EntityReference persona =
        term.getContextPersona() == null
            ? null
            : normalize(term.getContextPersona(), Entity.PERSONA, resolver);
    final List<TermContextPersonaOverride> overrides =
        normalizeOverrides(term.getContextPersonaOverrides(), resolver);
    term.setContextPersona(persona);
    term.setContextPersonaOverrides(overrides);
  }

  public static void validateChangedAndNormalize(
      final GlossaryTerm original,
      final GlossaryTerm updated,
      final BiFunction<String, UUID, EntityReference> resolver) {
    if (!Objects.equals(original.getContextPersona(), updated.getContextPersona())
        || !listOrEmpty(original.getContextPersonaOverrides())
            .equals(listOrEmpty(updated.getContextPersonaOverrides()))) {
      validateAndNormalize(updated, resolver);
    }
  }

  private static List<TermContextPersonaOverride> normalizeOverrides(
      final List<TermContextPersonaOverride> overrides,
      final BiFunction<String, UUID, EntityReference> resolver) {
    if (listOrEmpty(overrides).size() > MAX_USER_OVERRIDES) {
      throw new IllegalArgumentException("A glossary term supports at most 100 Persona overrides");
    }
    final Set<UUID> users = new HashSet<>();
    final List<TermContextPersonaOverride> normalized = new ArrayList<>();
    for (final TermContextPersonaOverride override : listOrEmpty(overrides)) {
      normalized.add(normalizeOverride(override, users, resolver));
    }
    return normalized;
  }

  private static TermContextPersonaOverride normalizeOverride(
      final TermContextPersonaOverride override,
      final Set<UUID> users,
      final BiFunction<String, UUID, EntityReference> resolver) {
    if (override == null) {
      throw new IllegalArgumentException("Persona override must contain a user and a Persona");
    }
    final EntityReference user = normalize(override.getUser(), Entity.USER, resolver);
    if (!users.add(user.getId())) {
      throw new IllegalArgumentException(
          "Persona override user occurs more than once: " + user.getId());
    }
    return new TermContextPersonaOverride()
        .withUser(user)
        .withPersona(normalize(override.getPersona(), Entity.PERSONA, resolver));
  }

  private static EntityReference normalize(
      final EntityReference reference,
      final String entityType,
      final BiFunction<String, UUID, EntityReference> resolver) {
    if (reference == null || reference.getId() == null || !entityType.equals(reference.getType())) {
      throw new IllegalArgumentException(
          "Persona context binding requires a " + entityType + " reference with its UUID and type");
    }
    return resolver.apply(entityType, reference.getId());
  }
}
