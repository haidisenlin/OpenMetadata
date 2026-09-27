/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service.jdbi3;

import static org.openmetadata.service.jdbi3.locator.ConnectionType.MYSQL;
import static org.openmetadata.service.jdbi3.locator.ConnectionType.POSTGRES;

import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.openmetadata.schema.type.RecordBinding;
import org.openmetadata.service.jdbi3.locator.ConnectionAwareSqlUpdate;
import org.openmetadata.service.util.jdbi.BindJson;
import org.openmetadata.service.util.jdbi.BindUUID;

@RegisterRowMapper(RecordBindingMapper.class)
public interface RecordBindingDAO {
  @ConnectionAwareSqlUpdate(
      value =
          """
          INSERT INTO glossary_record_binding
              (id, termId, assetId, assetType, locatorType, locator, locatorHash, displayName,
               status, createdAt, createdBy, updatedAt, updatedBy)
          VALUES
              (:id, :termId, :assetId, :assetType, :locatorType, :locator, :locatorHash,
               :displayName, :status, :createdAt, :createdBy, :updatedAt, :updatedBy)
          """,
      connectionType = MYSQL)
  @ConnectionAwareSqlUpdate(
      value =
          """
          INSERT INTO glossary_record_binding
              (id, termId, assetId, assetType, locatorType, locator, locatorHash, displayName,
               status, createdAt, createdBy, updatedAt, updatedBy)
          VALUES
              (:id, :termId, :assetId, :assetType, :locatorType, :locator::jsonb, :locatorHash,
               :displayName, :status, :createdAt, :createdBy, :updatedAt, :updatedBy)
          """,
      connectionType = POSTGRES)
  void insert(
      @BindUUID("id") UUID id,
      @BindUUID("termId") UUID termId,
      @BindUUID("assetId") UUID assetId,
      @Bind("assetType") String assetType,
      @Bind("locatorType") String locatorType,
      @BindJson("locator") String locator,
      @Bind("locatorHash") String locatorHash,
      @Bind("displayName") String displayName,
      @Bind("status") String status,
      @Bind("createdAt") long createdAt,
      @Bind("createdBy") String createdBy,
      @Bind("updatedAt") long updatedAt,
      @Bind("updatedBy") String updatedBy);

  @SqlQuery(
      """
      SELECT rb.id, rb.termId, rb.assetId, rb.assetType, rb.locatorType, rb.locator,
             rb.locatorHash, rb.displayName, rb.status, rb.createdAt, rb.createdBy,
             rb.updatedAt, rb.updatedBy
      FROM glossary_record_binding rb
      INNER JOIN glossary_term_entity gt ON gt.id = rb.termId
      WHERE rb.id = :id AND rb.termId = :termId AND gt.deleted = FALSE
      """)
  RecordBinding findByIdAndTermId(@BindUUID("id") UUID id, @BindUUID("termId") UUID termId);

  @SqlQuery(
      """
      SELECT rb.id, rb.termId, rb.assetId, rb.assetType, rb.locatorType, rb.locator,
             rb.locatorHash, rb.displayName, rb.status, rb.createdAt, rb.createdBy,
             rb.updatedAt, rb.updatedBy
      FROM glossary_record_binding rb
      INNER JOIN glossary_term_entity gt ON gt.id = rb.termId
      WHERE rb.termId = :termId AND gt.deleted = FALSE
      ORDER BY rb.updatedAt DESC, rb.id
      LIMIT :limit OFFSET :offset
      """)
  List<RecordBinding> listByTermId(
      @BindUUID("termId") UUID termId, @Bind("limit") int limit, @Bind("offset") int offset);

  @SqlQuery(
      """
      SELECT COUNT(*)
      FROM glossary_record_binding rb
      INNER JOIN glossary_term_entity gt ON gt.id = rb.termId
      WHERE rb.termId = :termId AND gt.deleted = FALSE
      """)
  int countByTermId(@BindUUID("termId") UUID termId);

  @SqlQuery(
      """
      SELECT rb.id, rb.termId, rb.assetId, rb.assetType, rb.locatorType, rb.locator,
             rb.locatorHash, rb.displayName, rb.status, rb.createdAt, rb.createdBy,
             rb.updatedAt, rb.updatedBy
      FROM glossary_record_binding rb
      INNER JOIN glossary_term_entity gt ON gt.id = rb.termId
      WHERE rb.assetId = :assetId
        AND rb.assetType = :assetType
        AND rb.locatorType = :locatorType
        AND rb.locatorHash = :locatorHash
        AND gt.deleted = FALSE
      ORDER BY rb.updatedAt DESC, rb.id
      """)
  List<RecordBinding> resolve(
      @BindUUID("assetId") UUID assetId,
      @Bind("assetType") String assetType,
      @Bind("locatorType") String locatorType,
      @Bind("locatorHash") String locatorHash);

  @SqlUpdate("DELETE FROM glossary_record_binding WHERE id = :id AND termId = :termId")
  int deleteByIdAndTermId(@BindUUID("id") UUID id, @BindUUID("termId") UUID termId);

  @SqlUpdate("DELETE FROM glossary_record_binding WHERE termId = :termId")
  int deleteByTermId(@BindUUID("termId") UUID termId);

  @SqlUpdate(
      "DELETE FROM glossary_record_binding WHERE assetId = :assetId AND assetType = :assetType")
  int deleteByAsset(@BindUUID("assetId") UUID assetId, @Bind("assetType") String assetType);
}
