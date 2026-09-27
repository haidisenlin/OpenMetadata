/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service.migration.utils.v202;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecordBindingSqlMigrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"mysql", "postgres"})
  void fr002Ac001CreatesRecordBindingStorageForBothProviders(String dialect) throws IOException {
    String sql = readMigration(dialect);

    assertTrue(sql.contains("create table if not exists glossary_record_binding"));
    for (String column :
        new String[] {"termid", "assetid", "assettype", "locatortype", "locatorhash"}) {
      assertTrue(sql.contains(column), "missing column " + column);
    }
    assertTrue(sql.contains("termid, assetid, locatortype, locatorhash"));
    assertTrue(sql.contains("assetid, locatorhash"));
  }

  private String readMigration(String dialect) throws IOException {
    return Files.readString(
            repositoryRoot()
                .resolve("bootstrap/sql/migrations/native/2.0.2")
                .resolve(dialect)
                .resolve("schemaChanges.sql"))
        .toLowerCase(Locale.ROOT);
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.exists(current.resolve("bootstrap/sql/schema/mysql.sql"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Unable to locate the OpenMetadata repository root");
    }
    return current;
  }
}
