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
  Card,
  Typography,
} from '@openmetadata/ui-core-components';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { GlossaryTerm } from '../../../../generated/entity/data/glossaryTerm';
import { getEntityName } from '../../../../utils/EntityNameUtils';
import { useGenericContext } from '../../../Customization/GenericProvider/GenericContext';
import TermPersonaContextForm from './TermPersonaContextForm';

const TermPersonaContext = () => {
  const { t } = useTranslation();
  const {
    data: term,
    permissions,
    isVersionView,
    onUpdate,
  } = useGenericContext<GlossaryTerm>();
  const [isEditing, setIsEditing] = useState(false);
  const canEdit = permissions.EditAll && !isVersionView;

  const handleSave = async (updated: GlossaryTerm) => {
    await onUpdate(updated);
    setIsEditing(false);
  };

  return (
    <Card className="tw:m-4" data-testid="term-persona-context">
      <Card.Header
        extra={
          canEdit && !isEditing ? (
            <Button
              color="secondary"
              data-testid="edit-term-persona-context"
              onPress={() => setIsEditing(true)}>
              {t('label.edit')}
            </Button>
          ) : undefined
        }
        title={t('label.ai-context')}
      />
      <Card.Content>
        <Box direction="col" gap={4}>
          <Typography>{t('message.term-context-persona-help')}</Typography>
          {canEdit && isEditing ? (
            <TermPersonaContextForm
              term={term}
              onCancel={() => setIsEditing(false)}
              onSave={handleSave}
            />
          ) : (
            <Box direction="col" gap={4}>
              <Box direction="col" gap={1}>
                <Typography weight="semibold">
                  {t('label.default-persona')}
                </Typography>
                <Typography data-testid="term-context-default">
                  {term.contextPersona
                    ? getEntityName(term.contextPersona)
                    : t('label.not-set')}
                </Typography>
              </Box>
              <Box direction="col" gap={2}>
                <Typography weight="semibold">
                  {t('label.user-context-overrides')}
                </Typography>
                {(term.contextPersonaOverrides ?? []).map(
                  ({ user, persona }) => (
                    <Box
                      data-testid={`term-context-user-${user.id}`}
                      gap={4}
                      key={user.id}
                      wrap="wrap">
                      <Typography>{getEntityName(user)}</Typography>
                      <Typography>{getEntityName(persona)}</Typography>
                    </Box>
                  )
                )}
                {!term.contextPersonaOverrides?.length ? (
                  <Typography>{t('label.none')}</Typography>
                ) : null}
              </Box>
            </Box>
          )}
        </Box>
      </Card.Content>
    </Card>
  );
};

export default TermPersonaContext;
