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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openmetadata.mcp.util.McpResponseTrim;
import org.openmetadata.schema.entity.teams.Persona;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.Entity;
import org.openmetadata.service.aicontext.PersonaContextAccess;
import org.openmetadata.service.aicontext.PersonaContextBuilder;
import org.openmetadata.service.aicontext.PersonaContextCache;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

/** Shared authorization, materialization and stable pagination for explicit and bound contexts. */
final class PersonaContextReader {
  private static final int PART_RESPONSE_BUDGET = McpResponseTrim.MAX_RESPONSE_CHARS - 10_000;
  private static final String FORMAT_JSON = "json";
  private static final String FORMAT_MARKDOWN = "markdown";
  private static final String PERSONA_FIELDS = "contextDefinition,users";
  private final CatalogSecurityContext securityContext;

  record Page(
      String format,
      String content,
      int part,
      int totalParts,
      boolean hasMore,
      String fingerprint,
      String personaName) {}

  PersonaContextReader(final CatalogSecurityContext securityContext) {
    this.securityContext = securityContext;
  }

  Page read(final String personaName, final String format, final int part) {
    final Persona persona =
        personaName == null || personaName.isBlank()
            ? PersonaContextAccess.activePersona(securityContext)
            : Entity.getEntityByName(
                Entity.PERSONA, personaName, PERSONA_FIELDS, Include.NON_DELETED);
    return read(persona, format, part);
  }

  Page read(final EntityReference reference) {
    final Persona persona =
        Entity.getEntity(Entity.PERSONA, reference.getId(), PERSONA_FIELDS, Include.NON_DELETED);
    return read(persona, FORMAT_MARKDOWN, 1);
  }

  private Page read(final Persona persona, final String format, final int part) {
    PersonaContextAccess.authorize(securityContext, persona);
    requireEnabled(persona);
    final PersonaContextBuilder.MaterializedPersonaContext materialized =
        PersonaContextCache.getInstance().get(persona, false).value();
    final String outputFormat =
        FORMAT_JSON.equalsIgnoreCase(format) ? FORMAT_JSON : FORMAT_MARKDOWN;
    final String content =
        FORMAT_JSON.equals(outputFormat)
            ? JsonUtils.pojoToJson(materialized.context())
            : materialized.markdown();
    return page(
        content,
        outputFormat,
        part,
        materialized.context().getFingerprint(),
        persona.getFullyQualifiedName());
  }

  static void requireEnabled(final Persona persona) {
    if (persona.getContextDefinition() != null
        && Boolean.FALSE.equals(persona.getContextDefinition().getEnabled())) {
      throw new IllegalStateException("Persona context is disabled");
    }
  }

  static Page page(
      final String content,
      final String format,
      final int part,
      final String fingerprint,
      final String personaName) {
    final List<String> parts = split(content);
    if (part < 1 || part > parts.size()) {
      throw new IllegalArgumentException("part must be between 1 and " + parts.size());
    }
    return new Page(
        format,
        parts.get(part - 1),
        part,
        parts.size(),
        part < parts.size(),
        fingerprint,
        personaName);
  }

  static List<String> split(final String content) {
    final List<String> parts = new ArrayList<>();
    if (content == null || content.isEmpty()) {
      parts.add("");
    } else {
      int start = 0;
      while (start < content.length()) {
        final int end = partEnd(content, start);
        parts.add(content.substring(start, end));
        start = end;
      }
    }
    return List.copyOf(parts);
  }

  private static int partEnd(final String content, final int start) {
    final int candidate = largestSerializableEnd(content, start);
    final int lineEnd = content.lastIndexOf('\n', candidate - 1);
    return candidate < content.length() && lineEnd > start ? lineEnd + 1 : candidate;
  }

  private static int largestSerializableEnd(final String content, final int start) {
    int low = start + 1;
    int high = content.length();
    int result = low;
    while (low <= high) {
      final int midpoint = low + (high - low) / 2;
      if (McpResponseTrim.serializedLength(Map.of("content", content.substring(start, midpoint)))
          <= PART_RESPONSE_BUDGET) {
        result = midpoint;
        low = midpoint + 1;
      } else {
        high = midpoint - 1;
      }
    }
    return preserveSurrogatePair(content, start, result);
  }

  private static int preserveSurrogatePair(final String content, final int start, final int end) {
    return end > start
            && end < content.length()
            && Character.isHighSurrogate(content.charAt(end - 1))
            && Character.isLowSurrogate(content.charAt(end))
        ? end - 1
        : end;
  }
}
