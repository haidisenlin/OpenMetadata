/*
 *  Copyright 2026 Collate.
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

import {
  Button,
  Dialog,
  Input,
  Modal,
  ModalOverlay,
  Select,
  Typography,
} from '@openmetadata/ui-core-components';
import { useMemo, useRef, useState } from 'react';
import type { Key } from 'react-aria-components';
import { useTranslation } from 'react-i18next';
import { EntityType } from '../../../../../enums/entity.enum';
import { SearchIndex } from '../../../../../enums/search.enum';
import {
  type CreateGlossaryRecordBinding,
  RecordLocatorType,
} from '../../../../../generated/api/data/createGlossaryRecordBinding';
import type { DataAssetOption } from '../../../../DataAssets/DataAssetAsyncSelectList/DataAssetAsyncSelectList.interface';
import DataAssetSelectList from '../../../../DataAssets/DataAssetSelectList/DataAssetSelectList';
import {
  type RecordBindingFormData,
  type RecordLocatorValue,
  type RecordLocatorValueTypeFormValue,
  transformRecordBindingFormData,
} from './RecordBindingForm.utils';

interface RecordBindingFormProps {
  isSubmitting: boolean;
  open: boolean;
  onCancel: () => void;
  onSubmit: (request: CreateGlossaryRecordBinding) => Promise<void> | void;
}

interface KeyRow {
  fieldFqn: string;
  id: string;
  value: string;
  valueType: RecordLocatorValueTypeFormValue;
}

interface PathParameterRow {
  id: string;
  key: string;
  value: string;
}

const VALUE_TYPES: RecordLocatorValueTypeFormValue[] = [
  'STRING',
  'NUMBER',
  'BOOLEAN',
  'DATE',
  'DATETIME',
];
const DECIMAL_PATTERN = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/;
const FORBIDDEN_LOCATOR_KEYS = new Set([
  'auth',
  'authorization',
  'apikey',
  'accesstoken',
  'cookie',
  'header',
  'headers',
  'password',
  'refreshtoken',
  'secret',
  'setcookie',
  'token',
]);
const isCredentialKey = (key: string) =>
  FORBIDDEN_LOCATOR_KEYS.has(key.replace(/[^a-z0-9]/gi, '').toLowerCase());

const createKeyRow = (id: string): KeyRow => ({
  fieldFqn: '',
  id,
  value: '',
  valueType: 'STRING',
});

const toRuntimeValue = (
  value: string,
  valueType: RecordLocatorValueTypeFormValue
): RecordLocatorValue => {
  if (valueType === 'NUMBER') {
    // Keep the lexical decimal representation. Converting through JavaScript Number would merge
    // distinct database keys above 2^53 before the request reaches the server.
    return value.trim();
  }

  if (valueType === 'BOOLEAN') {
    return value === 'true';
  }

  return value;
};

const hasValidValue = (
  value: string,
  valueType: RecordLocatorValueTypeFormValue
) =>
  value.trim().length > 0 &&
  (valueType !== 'NUMBER' || DECIMAL_PATTERN.test(value.trim()));

const RecordBindingForm = ({
  isSubmitting,
  open,
  onCancel,
  onSubmit,
}: RecordBindingFormProps) => {
  const { t } = useTranslation();
  const rowId = useRef(1);
  const [locatorType, setLocatorType] = useState<RecordLocatorType>(
    RecordLocatorType.TablePrimaryKey
  );
  const [selectedAsset, setSelectedAsset] = useState<DataAssetOption>();
  const [displayName, setDisplayName] = useState('');
  const [keyRows, setKeyRows] = useState<KeyRow[]>([createKeyRow('key-0')]);
  const [businessKeyPath, setBusinessKeyPath] = useState('');
  const [businessKeyValue, setBusinessKeyValue] = useState('');
  const [businessKeyValueType, setBusinessKeyValueType] =
    useState<RecordLocatorValueTypeFormValue>('STRING');
  const [pathParameters, setPathParameters] = useState<PathParameterRow[]>([]);

  const assetLabel =
    locatorType === RecordLocatorType.TablePrimaryKey
      ? t('label.table')
      : t('label.api-endpoint');
  const searchIndex =
    locatorType === RecordLocatorType.TablePrimaryKey
      ? SearchIndex.TABLE
      : SearchIndex.API_ENDPOINT;
  const expectedAssetType =
    locatorType === RecordLocatorType.TablePrimaryKey
      ? EntityType.TABLE
      : EntityType.API_ENDPOINT;

  const isValid = useMemo(() => {
    if (
      !selectedAsset?.reference.id ||
      selectedAsset.reference.type !== expectedAssetType
    ) {
      return false;
    }

    if (locatorType === RecordLocatorType.TablePrimaryKey) {
      return (
        keyRows.length > 0 &&
        keyRows.every(
          ({ fieldFqn, value, valueType }) =>
            fieldFqn.trim().length > 0 && hasValidValue(value, valueType)
        )
      );
    }

    const hasValidBusinessKey =
      businessKeyPath.trim().startsWith('/') &&
      hasValidValue(businessKeyValue, businessKeyValueType);
    const hasValidPathParameters = pathParameters.every(
      ({ key, value }) =>
        (key.trim().length === 0 && value.trim().length === 0) ||
        (key.trim().length > 0 &&
          value.trim().length > 0 &&
          !isCredentialKey(key.trim()))
    );

    return hasValidBusinessKey && hasValidPathParameters;
  }, [
    businessKeyPath,
    businessKeyValue,
    businessKeyValueType,
    keyRows,
    locatorType,
    pathParameters,
    expectedAssetType,
    selectedAsset,
  ]);

  const updateKeyRow = (id: string, updates: Partial<KeyRow>) => {
    setKeyRows((rows) =>
      rows.map((row) => (row.id === id ? { ...row, ...updates } : row))
    );
  };

  const updatePathParameter = (
    id: string,
    updates: Partial<PathParameterRow>
  ) => {
    setPathParameters((rows) =>
      rows.map((row) => (row.id === id ? { ...row, ...updates } : row))
    );
  };

  const renderValueInput = (
    value: string,
    valueType: RecordLocatorValueTypeFormValue,
    onChange: (value: string) => void
  ) => {
    if (valueType === 'BOOLEAN') {
      return (
        <Select
          isRequired
          label={t('label.value')}
          selectedKey={value || 'true'}
          onSelectionChange={(key) => onChange(String(key ?? 'true'))}>
          <Select.Item id="true" label={t('label.true')} />
          <Select.Item id="false" label={t('label.false')} />
        </Select>
      );
    }

    return (
      <Input
        isRequired
        label={t('label.value')}
        type={valueType === 'NUMBER' ? 'number' : 'text'}
        value={value}
        onChange={onChange}
      />
    );
  };

  const handleLocatorTypeChange = (key: Key | null) => {
    if (
      key !== RecordLocatorType.TablePrimaryKey &&
      key !== RecordLocatorType.APIResource
    ) {
      return;
    }

    setLocatorType(key);
    setSelectedAsset(undefined);
  };

  const handleSubmit = async () => {
    if (!isValid || !selectedAsset) {
      return;
    }

    const commonData = {
      asset: {
        id: selectedAsset.reference.id,
        type: selectedAsset.reference.type,
      },
      displayName: displayName.trim() || undefined,
    };
    let formData: RecordBindingFormData;

    if (locatorType === RecordLocatorType.TablePrimaryKey) {
      formData = {
        ...commonData,
        keys: keyRows.map(({ fieldFqn, value, valueType }) => ({
          fieldFqn: fieldFqn.trim(),
          value: toRuntimeValue(value, valueType),
          valueType,
        })),
      };
    } else {
      const validPathParameters = pathParameters
        .filter(({ key, value }) => key.trim() && value.trim())
        .map(({ key, value }) => ({ key: key.trim(), value }));
      formData = {
        ...commonData,
        businessKey: {
          jsonPointer: businessKeyPath.trim(),
          value: toRuntimeValue(businessKeyValue, businessKeyValueType),
          valueType: businessKeyValueType,
        },
        ...(validPathParameters.length > 0
          ? { pathParameters: validPathParameters }
          : {}),
      };
    }

    await onSubmit(transformRecordBindingFormData(formData));
  };

  return (
    <ModalOverlay
      isDismissable={!isSubmitting}
      isOpen={open}
      onOpenChange={(isOpen) => !isOpen && !isSubmitting && onCancel()}>
      <Modal>
        <Dialog
          title={t('label.add-entity', {
            entity: t('label.record-plural'),
          })}
          width={680}
          onClose={() => !isSubmitting && onCancel()}>
          <Dialog.Content>
            <div className="tw:flex tw:flex-col tw:gap-5">
              <Select
                isRequired
                label={t('label.asset-type')}
                selectedKey={locatorType}
                onSelectionChange={handleLocatorTypeChange}>
                <Select.Item
                  id={RecordLocatorType.TablePrimaryKey}
                  label={t('label.table')}
                />
                <Select.Item
                  id={RecordLocatorType.APIResource}
                  label={t('label.api-endpoint')}
                />
              </Select>

              <div className="tw:flex tw:flex-col tw:gap-2">
                <Typography size="text-sm" weight="semibold">
                  {t('label.select-entity', { entity: assetLabel })}
                </Typography>
                <DataAssetSelectList
                  key={locatorType}
                  renderTrigger={({ open: openPicker }) => (
                    <Button
                      className="tw:w-full tw:justify-start"
                      color="secondary"
                      onPress={openPicker}>
                      {selectedAsset?.displayName ??
                        t('label.select-entity', { entity: assetLabel })}
                    </Button>
                  )}
                  searchIndex={searchIndex}
                  selectionMode="single"
                  value={selectedAsset}
                  onChange={(option) =>
                    setSelectedAsset(Array.isArray(option) ? option[0] : option)
                  }
                />
              </div>

              <Input
                label={t('label.display-name')}
                value={displayName}
                onChange={setDisplayName}
              />

              {locatorType === RecordLocatorType.TablePrimaryKey ? (
                <div className="tw:flex tw:flex-col tw:gap-4">
                  {keyRows.map((row) => (
                    <div
                      className="tw:grid tw:grid-cols-1 tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:p-4 tw:md:grid-cols-4"
                      key={row.id}>
                      <Input
                        isRequired
                        label={t('label.field')}
                        value={row.fieldFqn}
                        onChange={(fieldFqn) =>
                          updateKeyRow(row.id, { fieldFqn })
                        }
                      />
                      <Select
                        isRequired
                        label={t('label.type')}
                        selectedKey={row.valueType}
                        onSelectionChange={(key) => {
                          const valueType = String(
                            key ?? 'STRING'
                          ) as RecordLocatorValueTypeFormValue;
                          updateKeyRow(row.id, {
                            value: valueType === 'BOOLEAN' ? 'true' : '',
                            valueType,
                          });
                        }}>
                        {VALUE_TYPES.map((valueType) => (
                          <Select.Item
                            id={valueType}
                            key={valueType}
                            label={valueType}
                          />
                        ))}
                      </Select>
                      {renderValueInput(row.value, row.valueType, (value) =>
                        updateKeyRow(row.id, { value })
                      )}
                      <Button
                        className="tw:self-end"
                        color="tertiary-destructive"
                        isDisabled={keyRows.length === 1}
                        onPress={() =>
                          setKeyRows((rows) =>
                            rows.filter(({ id }) => id !== row.id)
                          )
                        }>
                        {t('label.remove')}
                      </Button>
                    </div>
                  ))}
                  <Button
                    color="secondary"
                    onPress={() =>
                      setKeyRows((rows) => [
                        ...rows,
                        createKeyRow(`key-${rowId.current++}`),
                      ])
                    }>
                    {t('label.add-entity', { entity: t('label.field') })}
                  </Button>
                </div>
              ) : (
                <div className="tw:flex tw:flex-col tw:gap-4">
                  <div className="tw:grid tw:grid-cols-1 tw:gap-3 tw:rounded-lg tw:border tw:border-secondary tw:p-4 tw:md:grid-cols-3">
                    <Input
                      isRequired
                      label={t('label.path')}
                      value={businessKeyPath}
                      onChange={setBusinessKeyPath}
                    />
                    <Select
                      isRequired
                      label={t('label.type')}
                      selectedKey={businessKeyValueType}
                      onSelectionChange={(key) => {
                        const valueType = String(
                          key ?? 'STRING'
                        ) as RecordLocatorValueTypeFormValue;
                        setBusinessKeyValueType(valueType);
                        setBusinessKeyValue(
                          valueType === 'BOOLEAN' ? 'true' : ''
                        );
                      }}>
                      {VALUE_TYPES.map((valueType) => (
                        <Select.Item
                          id={valueType}
                          key={valueType}
                          label={valueType}
                        />
                      ))}
                    </Select>
                    {renderValueInput(
                      businessKeyValue,
                      businessKeyValueType,
                      setBusinessKeyValue
                    )}
                  </div>

                  {pathParameters.map((row) => (
                    <div
                      className="tw:grid tw:grid-cols-1 tw:gap-3 tw:md:grid-cols-3"
                      key={row.id}>
                      <Input
                        label={t('label.key')}
                        value={row.key}
                        onChange={(key) => updatePathParameter(row.id, { key })}
                      />
                      <Input
                        label={t('label.value')}
                        value={row.value}
                        onChange={(value) =>
                          updatePathParameter(row.id, { value })
                        }
                      />
                      <Button
                        className="tw:self-end"
                        color="tertiary-destructive"
                        onPress={() =>
                          setPathParameters((rows) =>
                            rows.filter(({ id }) => id !== row.id)
                          )
                        }>
                        {t('label.remove')}
                      </Button>
                    </div>
                  ))}
                  <Button
                    color="secondary"
                    onPress={() =>
                      setPathParameters((rows) => [
                        ...rows,
                        {
                          id: `path-${rowId.current++}`,
                          key: '',
                          value: '',
                        },
                      ])
                    }>
                    {t('label.add-entity', {
                      entity: t('label.parameter'),
                    })}
                  </Button>
                </div>
              )}
            </div>
          </Dialog.Content>
          <Dialog.Footer>
            <Button
              color="secondary"
              isDisabled={isSubmitting}
              onPress={onCancel}>
              {t('label.cancel')}
            </Button>
            <Button
              color="primary"
              isDisabled={!isValid || isSubmitting}
              isLoading={isSubmitting}
              onPress={handleSubmit}>
              {t('label.create')}
            </Button>
          </Dialog.Footer>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
};

export default RecordBindingForm;
