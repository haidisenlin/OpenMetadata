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
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openmetadata.schema.type.aicontext.KnowledgeItem;
import org.openmetadata.service.aicontext.AIContextFinder.FoundContext;

class FindContextTermReferencesTest {
  @ParameterizedTest
  @ValueSource(strings = {"markdown", "json"})
  void preservesStructuredTermMatchesForBothFormats(String format) {
    UUID id = UUID.randomUUID();
    var term =
        new KnowledgeItem()
            .withId(id)
            .withType(KnowledgeItem.Type.GLOSSARY_TERM)
            .withName("UPH")
            .withFullyQualifiedName("Manufacturing.UPH")
            .withContent("Units per hour");
    var metric =
        new KnowledgeItem()
            .withType(KnowledgeItem.Type.METRIC)
            .withName("UPH metric")
            .withFullyQualifiedName("UPH");
    var result = FindContextTool.render(new FoundContext(List.of(term, metric), List.of()), format);

    assertThat(McpTermReferences.collect("find_context", result).terms())
        .extracting(McpTermReferences.Term::fullyQualifiedName)
        .containsExactly("Manufacturing.UPH");
    assertThat(McpTermReferences.collect("find_context", result).terms().getFirst().id())
        .isEqualTo(id);
  }
}
