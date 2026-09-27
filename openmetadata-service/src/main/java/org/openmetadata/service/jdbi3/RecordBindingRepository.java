/*
 *  Copyright 2026 Collate
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
package org.openmetadata.service.jdbi3;

import static org.openmetadata.common.utils.CommonUtil.listOrEmpty;
import static org.openmetadata.schema.type.Include.ALL;
import static org.openmetadata.schema.type.Include.NON_DELETED;
import static org.openmetadata.service.Entity.API_ENDPOINT;
import static org.openmetadata.service.Entity.GLOSSARY_TERM;
import static org.openmetadata.service.Entity.TABLE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openmetadata.schema.api.data.CreateGlossaryRecordBinding;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnConstraint;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.RecordBinding;
import org.openmetadata.schema.type.RecordBindingStatus;
import org.openmetadata.schema.type.RecordLocator;
import org.openmetadata.schema.type.RecordLocatorType;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.schema.utils.JsonUtils;
import org.openmetadata.schema.utils.ResultList;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.BadRequestException;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.RecordBindingLocatorUtil;
import org.openmetadata.service.util.RecordBindingLocatorUtil.CanonicalLocator;

public final class RecordBindingRepository {
  private static final String RECORD_BINDING = "RecordBinding";
  private static final String TABLE_VALIDATION_FIELDS = "columns,tableConstraints";

  private final RecordBindingDAO dao;

  public RecordBindingRepository(CollectionDAO daoCollection) {
    if (daoCollection == null) {
      throw new IllegalArgumentException("daoCollection is required");
    }
    dao = daoCollection.recordBindingDAO();
  }

  public RecordBinding create(UUID termId, CreateGlossaryRecordBinding request, String user) {
    EntityReference term = getTermReference(termId);
    if (user == null || user.isBlank()) {
      throw new IllegalArgumentException("user is required");
    }

    PreparedLocator prepared = prepare(request);
    UUID id = UUID.randomUUID();
    long now = System.currentTimeMillis();
    RecordBinding binding =
        new RecordBinding()
            .withId(id)
            .withTerm(term)
            .withAsset(prepared.asset())
            .withLocatorType(prepared.locatorType())
            .withLocator(prepared.locator())
            .withLocatorHash(prepared.locatorHash())
            .withDisplayName(request.getDisplayName())
            .withStatus(RecordBindingStatus.UNVERIFIED)
            .withCreatedAt(now)
            .withCreatedBy(user)
            .withUpdatedAt(now)
            .withUpdatedBy(user);

    dao.insert(
        id,
        termId,
        prepared.asset().getId(),
        prepared.asset().getType(),
        prepared.locatorType().value(),
        prepared.storageJson(),
        prepared.locatorHash(),
        request.getDisplayName(),
        RecordBindingStatus.UNVERIFIED.value(),
        now,
        user,
        now,
        user);
    return binding;
  }

  public RecordBinding get(UUID termId, UUID bindingId) {
    EntityReference term = getTermReference(termId);
    requireId(bindingId, "bindingId");
    RecordBinding binding = dao.findByIdAndTermId(bindingId, termId);
    if (binding == null) {
      throw recordBindingNotFound(bindingId);
    }
    return hydrate(binding, term, null);
  }

  public ResultList<RecordBinding> list(UUID termId, int limit, int offset) {
    validatePage(limit, offset);
    EntityReference term = getTermReference(termId);
    List<RecordBinding> bindings = dao.listByTermId(termId, limit, offset);
    if (bindings == null) {
      bindings = new ArrayList<>();
    }
    for (RecordBinding binding : bindings) {
      hydrate(binding, term, null);
    }
    return new ResultList<>(bindings, offset, limit, dao.countByTermId(termId));
  }

  public void delete(UUID termId, UUID bindingId) {
    getTermReference(termId);
    requireId(bindingId, "bindingId");
    if (dao.deleteByIdAndTermId(bindingId, termId) == 0) {
      throw recordBindingNotFound(bindingId);
    }
  }

  public List<RecordBinding> resolve(CreateGlossaryRecordBinding request) {
    PreparedLocator prepared = prepare(request);
    List<RecordBinding> bindings =
        dao.resolve(
            prepared.asset().getId(),
            prepared.asset().getType(),
            prepared.locatorType().value(),
            prepared.locatorHash());
    if (bindings == null) {
      return List.of();
    }
    for (RecordBinding binding : bindings) {
      hydrate(binding, null, prepared.asset());
    }
    return bindings;
  }

  private PreparedLocator prepare(CreateGlossaryRecordBinding request) {
    if (request == null) {
      throw new BadRequestException("request is required");
    }
    RecordLocatorType locatorType = request.getLocatorType();
    if (locatorType == null) {
      throw new BadRequestException("locatorType is required");
    }
    if (request.getLocator() == null) {
      throw new BadRequestException("locator is required");
    }

    EntityReference asset = getAssetReference(request.getAsset(), locatorType);
    Map<String, Object> locatorMap = new LinkedHashMap<>(JsonUtils.getMap(request.getLocator()));
    if (locatorType == RecordLocatorType.API_RESOURCE
        && listOrEmpty(request.getLocator().getKeys()).isEmpty()) {
      locatorMap.remove("keys");
    }
    CanonicalLocator canonical;
    try {
      canonical = RecordBindingLocatorUtil.canonicalize(locatorType.value(), locatorMap);
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException(exception.getMessage());
    }
    RecordLocator locator =
        RecordBindingLocatorUtil.parseCanonicalLocator(canonical.canonicalJson());
    if (locatorType == RecordLocatorType.API_RESOURCE) {
      locator.setKeys(null);
    } else {
      validateTableLocator(asset, locator);
    }
    return new PreparedLocator(
        asset, locatorType, locator, JsonUtils.pojoToJson(locator), canonical.locatorHash());
  }

  private EntityReference getAssetReference(
      EntityReference requestedAsset, RecordLocatorType locatorType) {
    if (requestedAsset == null || requestedAsset.getType() == null) {
      throw new BadRequestException("asset and asset.type are required");
    }
    String expectedType =
        switch (locatorType) {
          case TABLE_PRIMARY_KEY -> TABLE;
          case API_RESOURCE -> API_ENDPOINT;
        };
    if (!expectedType.equals(requestedAsset.getType())) {
      throw new BadRequestException(
          "locatorType " + locatorType.value() + " requires asset type " + expectedType);
    }
    if (requestedAsset.getId() == null && requestedAsset.getFullyQualifiedName() == null) {
      throw new BadRequestException("asset.id or asset.fullyQualifiedName is required");
    }
    return Entity.getEntityReference(requestedAsset, NON_DELETED);
  }

  private void validateTableLocator(EntityReference asset, RecordLocator locator) {
    Table table = Entity.getEntity(TABLE, asset.getId(), TABLE_VALIDATION_FIELDS, NON_DELETED);
    List<Column> columns = EntityUtil.getFlattenedEntityField(table.getColumns());
    Map<String, Column> columnsByFqn = new HashMap<>();
    for (Column column : columns) {
      if (column != null && column.getFullyQualifiedName() != null) {
        columnsByFqn.put(column.getFullyQualifiedName(), column);
      }
    }

    Set<String> selectedFields = new HashSet<>();
    for (var key : listOrEmpty(locator.getKeys())) {
      String fieldFqn = key.getFieldFqn();
      if (!columnsByFqn.containsKey(fieldFqn)) {
        throw new BadRequestException(
            "Record locator field does not belong to table "
                + table.getFullyQualifiedName()
                + ": "
                + fieldFqn);
      }
      selectedFields.add(fieldFqn);
    }
    if (!matchesUniqueConstraint(table, columns, columnsByFqn, selectedFields)) {
      throw new BadRequestException(
          "Record locator keys must exactly match a PRIMARY_KEY or UNIQUE constraint");
    }
  }

  private boolean matchesUniqueConstraint(
      Table table,
      List<Column> columns,
      Map<String, Column> columnsByFqn,
      Set<String> selectedFields) {
    Set<String> selectedColumnNames = new HashSet<>();
    for (String fieldFqn : selectedFields) {
      selectedColumnNames.add(columnsByFqn.get(fieldFqn).getName());
    }

    for (TableConstraint constraint : listOrEmpty(table.getTableConstraints())) {
      if (isUniqueConstraint(constraint)) {
        List<String> constrainedColumns = listOrEmpty(constraint.getColumns());
        Set<String> constraintFields = new HashSet<>(constrainedColumns);
        boolean exactCardinality =
            constraintFields.size() == constrainedColumns.size()
                && constrainedColumns.size() == selectedFields.size();
        if (exactCardinality
            && (constraintFields.equals(selectedColumnNames)
                || constraintFields.equals(selectedFields))) {
          return true;
        }
      }
    }

    Set<String> primaryKey = new HashSet<>();
    for (Column column : columns) {
      if (column.getConstraint() == ColumnConstraint.PRIMARY_KEY) {
        primaryKey.add(column.getFullyQualifiedName());
      } else if (column.getConstraint() == ColumnConstraint.UNIQUE
          && selectedFields.equals(Set.of(column.getFullyQualifiedName()))) {
        return true;
      }
    }
    return !primaryKey.isEmpty() && selectedFields.equals(primaryKey);
  }

  private boolean isUniqueConstraint(TableConstraint constraint) {
    return constraint.getConstraintType() == TableConstraint.ConstraintType.PRIMARY_KEY
        || constraint.getConstraintType() == TableConstraint.ConstraintType.UNIQUE;
  }

  private RecordBinding hydrate(
      RecordBinding binding, EntityReference knownTerm, EntityReference knownAsset) {
    EntityReference term =
        knownTerm == null
            ? Entity.getEntityReferenceById(GLOSSARY_TERM, binding.getTerm().getId(), NON_DELETED)
            : knownTerm;
    EntityReference asset =
        knownAsset == null
            ? Entity.getEntityReferenceById(
                binding.getAsset().getType(), binding.getAsset().getId(), ALL)
            : knownAsset;
    binding.setTerm(term);
    binding.setAsset(asset);
    if (binding.getLocatorType() == RecordLocatorType.API_RESOURCE
        && binding.getLocator() != null
        && listOrEmpty(binding.getLocator().getKeys()).isEmpty()) {
      binding.getLocator().setKeys(null);
    }
    return binding;
  }

  private EntityReference getTermReference(UUID termId) {
    requireId(termId, "termId");
    return Entity.getEntityReferenceById(GLOSSARY_TERM, termId, NON_DELETED);
  }

  private void validatePage(int limit, int offset) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be greater than zero");
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset must not be negative");
    }
  }

  private void requireId(UUID id, String name) {
    if (id == null) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private EntityNotFoundException recordBindingNotFound(UUID id) {
    return EntityNotFoundException.byMessage(
        CatalogExceptionMessage.entityNotFound(RECORD_BINDING, id));
  }

  private record PreparedLocator(
      EntityReference asset,
      RecordLocatorType locatorType,
      RecordLocator locator,
      String storageJson,
      String locatorHash) {}
}
