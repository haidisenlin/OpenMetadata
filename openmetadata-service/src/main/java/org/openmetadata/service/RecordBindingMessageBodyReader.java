/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import org.openmetadata.schema.api.data.CreateGlossaryRecordBinding;

/**
 * Preserves the lexical precision of numeric record identifiers at the HTTP boundary. Jackson's
 * default untyped-number handling uses {@code double}; two distinct DECIMAL primary keys above
 * 2^53 can therefore collapse to the same locator hash before repository validation runs.
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
@Priority(Priorities.ENTITY_CODER - 100)
public class RecordBindingMessageBodyReader
    implements MessageBodyReader<CreateGlossaryRecordBinding> {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

  @Override
  public boolean isReadable(
      Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
    return CreateGlossaryRecordBinding.class.equals(type);
  }

  @Override
  public CreateGlossaryRecordBinding readFrom(
      Class<CreateGlossaryRecordBinding> type,
      Type genericType,
      Annotation[] annotations,
      MediaType mediaType,
      MultivaluedMap<String, String> httpHeaders,
      InputStream entityStream)
      throws IOException, WebApplicationException {
    try {
      return MAPPER.readValue(entityStream, CreateGlossaryRecordBinding.class);
    } catch (JsonProcessingException exception) {
      throw new BadRequestException("Invalid record binding request", exception);
    }
  }
}
