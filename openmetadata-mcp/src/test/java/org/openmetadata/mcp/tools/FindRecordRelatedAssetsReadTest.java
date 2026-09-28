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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.Entity;
import org.openmetadata.service.security.AuthorizationException;
import org.openmetadata.service.security.Authorizer;
import org.openmetadata.service.security.auth.CatalogSecurityContext;

class FindRecordRelatedAssetsReadTest {
  private static final String FQN = "service.database.schema.reports";
  private static final CatalogSecurityContext SECURITY =
      new CatalogSecurityContext(() -> "reader", "https", "Bearer", Set.of());

  @Test
  void deniesHiddenAssetBeforeReadingItsCatalogRow() {
    Authorizer authorizer = mock(Authorizer.class);
    doThrow(new AuthorizationException("denied")).when(authorizer).authorize(any(), any(), any());
    AtomicInteger reads = new AtomicInteger();
    try (MockedStatic<Entity> catalog = mockStatic(Entity.class)) {
      catalog
          .when(() -> Entity.getEntityByName(Entity.TABLE, FQN, "tags", Include.NON_DELETED))
          .thenAnswer(
              invocation -> {
                reads.incrementAndGet();
                return new Table();
              });

      assertThatThrownBy(
              () ->
                  FindRecordRelatedAssetsTool.readVisibleAsset(
                      authorizer, SECURITY, Entity.TABLE, FQN))
          .isInstanceOf(AuthorizationException.class);
      assertThat(reads.get()).isZero();
    }
  }

  @Test
  void readsVisibleAssetsThroughTheNonDeletedEntityLookup() {
    Table table = new Table().withName("reports").withFullyQualifiedName(FQN);
    try (MockedStatic<Entity> catalog = mockStatic(Entity.class)) {
      catalog
          .when(() -> Entity.getEntityByName(Entity.TABLE, FQN, "tags", Include.NON_DELETED))
          .thenReturn(table);

      assertThat(
              FindRecordRelatedAssetsTool.readVisibleAsset(
                  mock(Authorizer.class), SECURITY, Entity.TABLE, FQN))
          .isSameAs(table);
    }
  }
}
