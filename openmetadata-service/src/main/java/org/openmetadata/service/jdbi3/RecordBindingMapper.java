/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package org.openmetadata.service.jdbi3;

import static org.openmetadata.service.Entity.GLOSSARY_TERM;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.RecordBinding;
import org.openmetadata.schema.type.RecordBindingStatus;
import org.openmetadata.schema.type.RecordLocatorType;
import org.openmetadata.service.util.RecordBindingLocatorUtil;

public class RecordBindingMapper implements RowMapper<RecordBinding> {
  @Override
  public RecordBinding map(ResultSet rs, StatementContext ctx) throws SQLException {
    EntityReference term =
        new EntityReference()
            .withId(UUID.fromString(rs.getString("termId")))
            .withType(GLOSSARY_TERM);
    EntityReference asset =
        new EntityReference()
            .withId(UUID.fromString(rs.getString("assetId")))
            .withType(rs.getString("assetType"));

    return new RecordBinding()
        .withId(UUID.fromString(rs.getString("id")))
        .withTerm(term)
        .withAsset(asset)
        .withLocatorType(RecordLocatorType.fromValue(rs.getString("locatorType")))
        .withLocator(RecordBindingLocatorUtil.parseCanonicalLocator(rs.getString("locator")))
        .withLocatorHash(rs.getString("locatorHash"))
        .withDisplayName(rs.getString("displayName"))
        .withStatus(RecordBindingStatus.fromValue(rs.getString("status")))
        .withCreatedAt(rs.getLong("createdAt"))
        .withCreatedBy(rs.getString("createdBy"))
        .withUpdatedAt(rs.getLong("updatedAt"))
        .withUpdatedBy(rs.getString("updatedBy"));
  }
}
