/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service.resources.glossary;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GlossaryTermRecordBindingResourceContractTest {
  @Test
  void fr002Ac002ExposesCreateAndListEndpoints() {
    assertEndpoint("/{id}/recordBindings", POST.class);
    assertEndpoint("/{id}/recordBindings", GET.class);
  }

  @Test
  void fr002Ac003ExposesScopedDeleteEndpoint() {
    assertEndpoint("/{id}/recordBindings/{bindingId}", DELETE.class);
  }

  @Test
  void fr002Ac004ExposesBodyBasedResolveEndpoint() {
    assertEndpoint("/recordBindings/resolve", POST.class);
  }

  private void assertEndpoint(String path, Class<? extends Annotation> httpMethod) {
    boolean exists =
        Arrays.stream(GlossaryTermResource.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Path.class))
            .filter(method -> path.equals(method.getAnnotation(Path.class).value()))
            .anyMatch(method -> method.isAnnotationPresent(httpMethod));

    assertTrue(exists, httpMethod.getSimpleName() + " " + path + " must be exposed");
  }
}
