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
import type { GlossaryTerm } from '../../../../generated/entity/data/glossaryTerm';
import type { EntityReference } from '../../../../generated/entity/type';
import type {
  ContextOption,
  ContextOverrideValue,
  TermPersonaContextValues,
} from './TermPersonaContext.types';

export const toContextOption = (value: EntityReference): ContextOption => ({
  id: value.id,
  label: value.displayName || value.name || value.id,
  value,
});

export const validateContextBindings = (overrides: ContextOverrideValue[]) => {
  const users = new Set<string>();
  for (const override of overrides) {
    if (!override.user || !override.persona) {
      return 'message.term-context-incomplete-override';
    }
    if (users.has(override.user.value.id)) {
      return 'message.term-context-duplicate-user';
    }
    users.add(override.user.value.id);
  }

  return undefined;
};

export const toContextBindings = (
  values: TermPersonaContextValues
): Pick<GlossaryTerm, 'contextPersona' | 'contextPersonaOverrides'> => ({
  contextPersona: values.contextPersona?.value,
  contextPersonaOverrides: values.overrides.flatMap(({ user, persona }) =>
    user && persona ? [{ user: user.value, persona: persona.value }] : []
  ),
});
