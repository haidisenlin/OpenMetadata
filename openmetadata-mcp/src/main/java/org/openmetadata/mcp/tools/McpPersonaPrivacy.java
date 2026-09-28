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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import org.openmetadata.schema.utils.JsonUtils;

/** Removes private per-user bindings from every MCP result, including mutation change histories. */
final class McpPersonaPrivacy {
  private static final String OVERRIDES = "contextPersonaOverrides";
  private static final String OVERRIDE_PATH = OVERRIDES + ".";

  private McpPersonaPrivacy() {}

  static Object sanitize(final Object result) {
    final JsonNode tree = JsonUtils.getObjectMapper().valueToTree(result);
    redact(tree);
    return JsonUtils.convertValue(tree, Object.class);
  }

  private static void redact(final JsonNode node) {
    if (node instanceof ObjectNode object) {
      final JsonNode overrides = object.path(OVERRIDES);
      if (!isSchemaDefinition(overrides)) {
        object.remove(OVERRIDES);
      }
      object.elements().forEachRemaining(McpPersonaPrivacy::redact);
    } else if (node instanceof ArrayNode array) {
      redactArray(array);
    }
  }

  private static boolean isSchemaDefinition(final JsonNode value) {
    return value.isObject() && value.has("type") && value.has("items");
  }

  private static void redactArray(final ArrayNode array) {
    final Iterator<JsonNode> values = array.elements();
    while (values.hasNext()) {
      final JsonNode value = values.next();
      if (isPrivateChange(value)) {
        values.remove();
      } else {
        redact(value);
      }
    }
  }

  private static boolean isPrivateChange(final JsonNode value) {
    final String name = value.path("name").asText("");
    final boolean change = value.has("oldValue") || value.has("newValue");
    return change && (OVERRIDES.equals(name) || name.startsWith(OVERRIDE_PATH));
  }
}
