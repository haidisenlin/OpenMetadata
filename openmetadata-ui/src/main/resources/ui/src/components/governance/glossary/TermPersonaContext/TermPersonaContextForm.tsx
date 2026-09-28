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
  Box,
  Button,
  FieldProp,
  FieldTypes,
  getField,
  HookForm,
  Typography,
} from '@openmetadata/ui-core-components';
import type { AxiosError } from 'axios';
import { debounce } from 'lodash';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useFieldArray, useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { SearchIndex } from '../../../../enums/search.enum';
import type { GlossaryTerm } from '../../../../generated/entity/data/glossaryTerm';
import type { EntityReference } from '../../../../generated/entity/type';
import { searchPersonas } from '../../../../rest/PersonaAPI';
import { searchQuery } from '../../../../rest/searchAPI';
import { getUsers } from '../../../../rest/userAPI';
import { formatUsersResponse } from '../../../../utils/APIUtils';
import { showErrorToast } from '../../../../utils/ToastUtils';
import type {
  ContextOption,
  TermPersonaContextValues,
} from './TermPersonaContext.types';
import {
  toContextBindings,
  toContextOption,
  validateContextBindings,
} from './TermPersonaContext.utils';

interface TermPersonaContextFormProps {
  term: GlossaryTerm;
  onSave: (updated: GlossaryTerm) => Promise<void>;
  onCancel: () => void;
}

const showAllOptions = () => true;

const TermPersonaContextForm = ({
  term,
  onSave,
  onCancel,
}: TermPersonaContextFormProps) => {
  const { t } = useTranslation();
  const form = useForm<TermPersonaContextValues>({
    defaultValues: {
      contextPersona: term.contextPersona
        ? toContextOption(term.contextPersona)
        : null,
      overrides: (term.contextPersonaOverrides ?? []).map(
        ({ user, persona }) => ({
          user: toContextOption(user),
          persona: toContextOption(persona),
        })
      ),
    },
  });
  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: 'overrides',
  });
  const [options, setOptions] = useState<{
    persona: ContextOption[];
    user: ContextOption[];
  }>({ persona: [], user: [] });
  const [validationError, setValidationError] = useState<string>();
  const requestIds = useRef({ persona: 0, user: 0 });

  const loadOptions = useCallback(
    async (type: 'persona' | 'user', query: string) => {
      const requestId = ++requestIds.current[type];
      try {
        let entities: Omit<EntityReference, 'type'>[];
        if (type === 'persona') {
          entities = await searchPersonas(query, 25);
        } else if (query) {
          entities = formatUsersResponse(
            (
              await searchQuery({
                query,
                pageNumber: 1,
                pageSize: 25,
                searchIndex: SearchIndex.USER,
              })
            ).hits.hits
          );
        } else {
          entities = (await getUsers({ limit: 25 })).data;
        }
        if (requestId === requestIds.current[type]) {
          setOptions((current) => ({
            ...current,
            [type]: entities.map((entity) =>
              toContextOption({
                id: entity.id,
                type,
                name: entity.name,
                displayName: entity.displayName,
                fullyQualifiedName: entity.fullyQualifiedName,
              })
            ),
          }));
        }
      } catch (error) {
        if (requestId === requestIds.current[type]) {
          setOptions((current) => ({ ...current, [type]: [] }));
          showErrorToast(error as AxiosError);
        }
      }
    },
    []
  );

  const searches = useMemo(
    () => ({
      persona: debounce(
        (query: string) => void loadOptions('persona', query),
        300
      ),
      user: debounce((query: string) => void loadOptions('user', query), 300),
    }),
    [loadOptions]
  );

  useEffect(() => {
    const currentRequestIds = requestIds.current;
    void Promise.all([loadOptions('persona', ''), loadOptions('user', '')]);

    return () => {
      searches.persona.cancel();
      searches.user.cancel();
      currentRequestIds.persona++;
      currentRequestIds.user++;
    };
  }, [loadOptions, searches]);

  const referenceField = (
    name: string,
    type: 'persona' | 'user',
    label: string
  ): FieldProp => ({
    name,
    label,
    type: FieldTypes.ASYNC_SELECT,
    props: {
      options: options[type],
      multiple: false,
      filterOption: showAllOptions,
      onSearchChange: searches[type],
      disabled: form.formState.isSubmitting,
    },
  });

  const submit = form.handleSubmit(async (values) => {
    const error = validateContextBindings(values.overrides);
    setValidationError(error);
    if (error) {
      return;
    }
    try {
      await onSave({ ...term, ...toContextBindings(values) });
    } catch (error) {
      showErrorToast(error as AxiosError);
    }
  });

  return (
    <HookForm form={form} onSubmit={submit}>
      <Box direction="col" gap={4}>
        <Box align="end" gap={3} wrap="wrap">
          <Box className="tw:min-w-0 tw:flex-1" direction="col">
            {getField(
              referenceField(
                'contextPersona',
                'persona',
                t('label.default-persona')
              )
            )}
          </Box>
          <Button
            color="secondary"
            data-testid="clear-default-context-persona"
            isDisabled={form.formState.isSubmitting}
            onPress={() => form.setValue('contextPersona', null)}>
            {t('label.clear')}
          </Button>
        </Box>
        <Typography weight="semibold">
          {t('label.user-context-overrides')}
        </Typography>
        {fields.map((field, index) => (
          <Box
            align="end"
            data-testid={`context-override-${index}`}
            gap={3}
            key={field.id}
            wrap="wrap">
            <Box className="tw:min-w-0 tw:flex-1" direction="col">
              {getField(
                referenceField(
                  `overrides.${index}.user`,
                  'user',
                  t('label.user')
                )
              )}
            </Box>
            <Box className="tw:min-w-0 tw:flex-1" direction="col">
              {getField(
                referenceField(
                  `overrides.${index}.persona`,
                  'persona',
                  t('label.persona')
                )
              )}
            </Box>
            <Button
              color="tertiary-destructive"
              data-testid={`remove-context-override-${index}`}
              isDisabled={form.formState.isSubmitting}
              onPress={() => {
                remove(index);
                document.getElementById('add-term-context-user')?.focus();
              }}>
              {t('label.remove')}
            </Button>
          </Box>
        ))}
        {validationError ? <p role="alert">{t(validationError)}</p> : null}
        <Box>
          <Button
            color="secondary"
            data-testid="add-context-override"
            id="add-term-context-user"
            isDisabled={form.formState.isSubmitting || fields.length >= 100}
            onPress={() => append({ user: null, persona: null })}>
            {t('label.add-entity', { entity: t('label.user') })}
          </Button>
        </Box>
        <Box gap={3} justify="end">
          <Button
            color="secondary"
            isDisabled={form.formState.isSubmitting}
            onPress={onCancel}>
            {t('label.cancel')}
          </Button>
          <Button
            data-testid="save-term-persona-context"
            isLoading={form.formState.isSubmitting}
            type="submit">
            {t('label.save')}
          </Button>
        </Box>
      </Box>
    </HookForm>
  );
};

export default TermPersonaContextForm;
