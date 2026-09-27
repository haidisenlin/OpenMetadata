/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service.jdbi3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RecordBindingPersistenceContractTest {
  @Test
  void fr002Ac005RegistersRecordBindingDao() {
    assertDoesNotThrow(() -> CollectionDAO.class.getMethod("recordBindingDAO"));
  }

  @Test
  void fr002Ac006DaoSupportsScopedCrudAndReverseLookup() throws Exception {
    Class<?> dao = Class.forName("org.openmetadata.service.jdbi3.RecordBindingDAO");
    Set<String> methods =
        Arrays.stream(dao.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());

    assertTrue(methods.containsAll(Set.of("insert", "findByIdAndTermId", "listByTermId")));
    assertTrue(methods.containsAll(Set.of("countByTermId", "resolve", "deleteByIdAndTermId")));
    assertTrue(methods.containsAll(Set.of("deleteByTermId", "deleteByAsset")));
  }

  @Test
  void fr002Ac007RepositoryExposesCrudAndResolveOperations() throws Exception {
    Class<?> repository = Class.forName("org.openmetadata.service.jdbi3.RecordBindingRepository");
    Set<String> methods =
        Arrays.stream(repository.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertTrue(methods.containsAll(Set.of("create", "list", "delete", "resolve")));
  }
}
