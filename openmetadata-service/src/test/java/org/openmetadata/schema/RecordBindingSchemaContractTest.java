/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.openmetadata.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class RecordBindingSchemaContractTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void fr001Ac001DefinesStableRecordBindingContract() throws Exception {
    JsonNode schema = schema("json/schema/type/recordBinding.json");

    assertTrue(schema.at("/properties/id").isObject());
    assertTrue(schema.at("/properties/term").isObject());
    assertTrue(schema.at("/properties/asset").isObject());
    assertTrue(schema.at("/properties/locatorType").isObject());
    assertTrue(schema.at("/properties/locator").isObject());
    assertTrue(schema.at("/properties/locatorHash").isObject());
  }

  @Test
  void fr001Sec001LocatorCannotPersistCredentials() throws Exception {
    JsonNode schema = schema("json/schema/type/recordBinding.json");
    JsonNode locatorProperties = schema.at("/definitions/recordLocator/properties");

    assertFalse(locatorProperties.has("headers"));
    assertFalse(locatorProperties.has("authorization"));
    assertFalse(locatorProperties.has("token"));
  }

  @Test
  void apiLocatorDoesNotReceiveAnInvalidEmptyKeysDefault() throws Exception {
    JsonNode schema = schema("json/schema/type/recordBinding.json");
    JsonNode keys = schema.at("/definitions/recordLocator/properties/keys");

    assertTrue(keys.has("default"));
    assertTrue(keys.get("default").isNull());
  }

  @Test
  void fr001Ac002DefinesCreateRequestWithoutServerManagedFields() throws Exception {
    JsonNode schema = schema("json/schema/api/data/createGlossaryRecordBinding.json");

    assertTrue(schema.at("/properties/asset").isObject());
    assertTrue(schema.at("/properties/locatorType").isObject());
    assertTrue(schema.at("/properties/locator").isObject());
    assertFalse(schema.at("/properties").has("locatorHash"));
    assertEquals(256, schema.at("/properties/displayName/maxLength").asInt());
  }

  private JsonNode schema(String path) throws Exception {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
    assertNotNull(stream, path + " must exist on the classpath");
    return objectMapper.readTree(stream);
  }
}
