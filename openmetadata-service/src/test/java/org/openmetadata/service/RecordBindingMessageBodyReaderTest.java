/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openmetadata.schema.api.data.CreateGlossaryRecordBinding;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.service.util.RecordBindingLocatorUtil;

class RecordBindingMessageBodyReaderTest {
  private final RecordBindingMessageBodyReader reader = new RecordBindingMessageBodyReader();

  @Test
  void preservesHighPrecisionDecimalRecordKeys() throws Exception {
    CreateGlossaryRecordBinding first = read("9007199254740992.1");
    CreateGlossaryRecordBinding second = read("9007199254740993.1");

    assertInstanceOf(BigDecimal.class, first.getLocator().getKeys().getFirst().getValue());
    assertNotEquals(hash(first), hash(second));
  }

  private CreateGlossaryRecordBinding read(String value) throws Exception {
    String json =
        """
        {
          "asset": {"id": "42e6b8c5-48f1-4dd8-b15e-ee1f1e046dd9", "type": "table"},
          "locatorType": "TABLE_PRIMARY_KEY",
          "locator": {
            "keys": [{
              "fieldFqn": "service.database.schema.equipment.id",
              "valueType": "NUMBER",
              "value": %s
            }]
          }
        }
        """
            .formatted(value);
    return reader.readFrom(
        CreateGlossaryRecordBinding.class,
        CreateGlossaryRecordBinding.class,
        new java.lang.annotation.Annotation[0],
        MediaType.APPLICATION_JSON_TYPE,
        new MultivaluedHashMap<>(),
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
  }

  private String hash(CreateGlossaryRecordBinding request) {
    Map<String, Object> locator = JsonUtils.getMap(request.getLocator());
    return RecordBindingLocatorUtil.canonicalize(request.getLocatorType().value(), locator)
        .locatorHash();
  }
}
