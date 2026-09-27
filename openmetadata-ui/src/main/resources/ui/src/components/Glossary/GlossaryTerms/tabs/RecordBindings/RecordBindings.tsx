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
  Badge,
  Button,
  Card,
  Skeleton,
  Typography,
} from '@openmetadata/ui-core-components';
import type { AxiosError } from 'axios';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { CreateGlossaryRecordBinding } from '../../../../../generated/api/data/createGlossaryRecordBinding';
import type { GlossaryTerm } from '../../../../../generated/entity/data/glossaryTerm';
import {
  type RecordBinding,
  RecordLocatorType,
} from '../../../../../generated/type/recordBinding';
import {
  createGlossaryTermRecordBinding,
  deleteGlossaryTermRecordBinding,
  getGlossaryTermRecordBindings,
} from '../../../../../rest/glossaryAPI';
import { showErrorToast } from '../../../../../utils/ToastUtils';
import DeleteModal from '../../../../common/DeleteModal/DeleteModal';
import { useGenericContext } from '../../../../Customization/GenericProvider/GenericContext';
import RecordBindingForm from './RecordBindingForm';

const PAGE_SIZE = 15;
const FORBIDDEN_LOCATOR_KEYS = new Set([
  'auth',
  'authorization',
  'header',
  'headers',
  'token',
]);

const getAssetName = (binding: RecordBinding) =>
  binding.asset.displayName ??
  binding.asset.name ??
  binding.asset.fullyQualifiedName ??
  binding.asset.id;

const getLocatorValues = (binding: RecordBinding): string[] => {
  const values =
    binding.locator.keys?.map(
      ({ fieldFqn, value }) => `${fieldFqn}: ${String(value)}`
    ) ?? [];

  if (binding.locator.environment) {
    values.push(binding.locator.environment);
  }

  Object.entries(binding.locator.pathParameters ?? {}).forEach(
    ([key, value]) => {
      if (!FORBIDDEN_LOCATOR_KEYS.has(key.trim().toLowerCase())) {
        values.push(`${key}: ${value}`);
      }
    }
  );

  if (binding.locator.businessKey) {
    values.push(
      `${binding.locator.businessKey.jsonPointer}: ${String(
        binding.locator.businessKey.value
      )}`
    );
  }

  return values;
};

const RecordBindings = () => {
  const { t } = useTranslation();
  const { data: glossaryTerm, permissions } = useGenericContext<GlossaryTerm>();
  const [bindings, setBindings] = useState<RecordBinding[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isFormOpen, setIsFormOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<RecordBinding>();
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);
  const canEdit =
    Boolean(permissions.EditGlossaryTerms) || Boolean(permissions.EditAll);

  const loadBindings = useCallback(
    async (requestedOffset: number) => {
      if (!glossaryTerm?.id) {
        setBindings([]);
        setTotal(0);
        setIsLoading(false);

        return;
      }

      setIsLoading(true);
      try {
        const response = await getGlossaryTermRecordBindings(glossaryTerm.id, {
          limit: PAGE_SIZE,
          offset: requestedOffset,
        });
        setBindings(response.data);
        setTotal(response.paging.total ?? 0);
      } catch (error) {
        setBindings([]);
        setTotal(0);
        showErrorToast(error as AxiosError);
      } finally {
        setIsLoading(false);
      }
    },
    [glossaryTerm?.id]
  );

  useEffect(() => {
    void loadBindings(offset);
  }, [loadBindings, offset]);

  const handleCreate = async (request: CreateGlossaryRecordBinding) => {
    setIsSubmitting(true);
    try {
      await createGlossaryTermRecordBinding(glossaryTerm.id, request);
      setIsFormOpen(false);
      if (offset === 0) {
        await loadBindings(0);
      } else {
        setOffset(0);
      }
    } catch (error) {
      showErrorToast(error as AxiosError);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) {
      return;
    }

    setIsDeleting(true);
    try {
      await deleteGlossaryTermRecordBinding(glossaryTerm.id, deleteTarget.id);
      setDeleteTarget(undefined);

      const nextOffset =
        bindings.length === 1 && offset > 0
          ? Math.max(0, offset - PAGE_SIZE)
          : offset;
      if (nextOffset === offset) {
        await loadBindings(offset);
      } else {
        setOffset(nextOffset);
      }
    } catch (error) {
      showErrorToast(error as AxiosError);
    } finally {
      setIsDeleting(false);
    }
  };

  return (
    <div className="tw:flex tw:h-full tw:flex-col tw:gap-4 tw:p-4">
      <div className="tw:flex tw:items-center tw:justify-between tw:gap-4">
        <Typography size="text-lg" weight="semibold">
          {t('label.record-plural')}
        </Typography>
        <div className="tw:flex tw:items-center tw:gap-2">
          <Button
            color="secondary"
            isDisabled={isLoading}
            onPress={() => void loadBindings(offset)}>
            {t('label.refresh')}
          </Button>
          {canEdit && (
            <Button
              color="primary"
              data-testid="add-record-binding"
              onPress={() => setIsFormOpen(true)}>
              {t('label.add-entity', { entity: t('label.record-plural') })}
            </Button>
          )}
        </div>
      </div>

      {isLoading ? (
        <div
          className="tw:flex tw:flex-col tw:gap-3"
          data-testid="record-bindings-loading">
          <Skeleton height={120} variant="rounded" />
          <Skeleton height={120} variant="rounded" />
        </div>
      ) : bindings.length === 0 ? (
        <Card className="tw:flex tw:min-h-40 tw:items-center tw:justify-center tw:p-6">
          <Typography className="tw:text-tertiary">
            {t('label.no-records')}
          </Typography>
        </Card>
      ) : (
        <div className="tw:flex tw:flex-col tw:gap-3">
          {bindings.map((binding) => {
            const assetName = getAssetName(binding);
            const locatorValues = getLocatorValues(binding);
            const typeLabel =
              binding.locatorType === RecordLocatorType.TablePrimaryKey
                ? t('label.table')
                : t('label.api-endpoint');

            return (
              <Card
                className="tw:flex tw:flex-col tw:gap-3 tw:p-4"
                data-testid={`record-binding-${binding.id}`}
                key={binding.id}>
                <div className="tw:flex tw:items-start tw:justify-between tw:gap-4">
                  <div className="tw:min-w-0">
                    <Typography
                      className="tw:text-primary"
                      size="text-md"
                      weight="semibold">
                      {binding.displayName ?? assetName}
                    </Typography>
                    <Typography className="tw:text-secondary" size="text-sm">
                      {assetName}
                    </Typography>
                    {binding.asset.fullyQualifiedName &&
                      binding.asset.fullyQualifiedName !== assetName && (
                        <Typography className="tw:text-tertiary" size="text-xs">
                          {binding.asset.fullyQualifiedName}
                        </Typography>
                      )}
                  </div>
                  {canEdit && (
                    <Button
                      color="tertiary-destructive"
                      size="sm"
                      onPress={() => setDeleteTarget(binding)}>
                      {t('label.delete')}
                    </Button>
                  )}
                </div>

                <div className="tw:flex tw:flex-wrap tw:items-center tw:gap-2">
                  <Typography className="tw:text-secondary" size="text-sm">
                    {t('label.type')}: {typeLabel}
                  </Typography>
                  <Typography className="tw:text-secondary" size="text-sm">
                    {t('label.status')}:
                  </Typography>
                  <Badge color="gray" size="sm" type="color">
                    {binding.status}
                  </Badge>
                </div>

                {locatorValues.length > 0 && (
                  <div className="tw:flex tw:flex-wrap tw:gap-2">
                    {locatorValues.map((value) => (
                      <Badge color="gray" key={value} size="sm" type="color">
                        {value}
                      </Badge>
                    ))}
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}

      {total > PAGE_SIZE && (
        <div className="tw:flex tw:justify-end tw:gap-2">
          <Button
            color="secondary"
            isDisabled={offset === 0 || isLoading}
            onPress={() =>
              setOffset((current) => Math.max(0, current - PAGE_SIZE))
            }>
            {t('label.previous')}
          </Button>
          <Button
            color="secondary"
            isDisabled={offset + PAGE_SIZE >= total || isLoading}
            onPress={() => setOffset((current) => current + PAGE_SIZE)}>
            {t('label.next')}
          </Button>
        </div>
      )}

      {isFormOpen && (
        <RecordBindingForm
          isSubmitting={isSubmitting}
          open={isFormOpen}
          onCancel={() => setIsFormOpen(false)}
          onSubmit={handleCreate}
        />
      )}

      {deleteTarget && (
        <DeleteModal
          entityTitle={deleteTarget.displayName ?? getAssetName(deleteTarget)}
          isDeleting={isDeleting}
          message={t('message.delete-entity-message', {
            entity: deleteTarget.displayName ?? getAssetName(deleteTarget),
          })}
          open={Boolean(deleteTarget)}
          onCancel={() => setDeleteTarget(undefined)}
          onDelete={handleDelete}
        />
      )}
    </div>
  );
};

export default RecordBindings;
