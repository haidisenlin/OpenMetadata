/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service.jdbi3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecordBindingCleanupContractTest {
  @Test
  void hardDeletingATermRemovesItsRecordBindings() throws IOException {
    String source = source("GlossaryTermRepository.java");

    assertTrue(source.contains("recordBindingDAO()"));
    assertTrue(source.contains("deleteByTermId"));
  }

  @Test
  void hardDeletingARecordAssetRemovesItsRecordBindings() throws IOException {
    for (String repository : new String[] {"TableRepository.java", "APIEndpointRepository.java"}) {
      String source = source(repository);

      assertTrue(source.contains("recordBindingDAO()"), repository);
      assertTrue(source.contains("deleteByAsset"), repository);
    }
  }

  private String source(String fileName) throws IOException {
    return Files.readString(
        repositoryRoot()
            .resolve("openmetadata-service/src/main/java/org/openmetadata/service/jdbi3")
            .resolve(fileName));
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.exists(current.resolve("openmetadata-service/pom.xml"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Unable to locate the OpenMetadata repository root");
    }
    return current;
  }
}
