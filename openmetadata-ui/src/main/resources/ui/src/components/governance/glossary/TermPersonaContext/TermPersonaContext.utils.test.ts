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
  toContextBindings,
  toContextOption,
  validateContextBindings,
} from './TermPersonaContext.utils';

const user = { id: 'user-id', type: 'user', name: 'Alice' };
const persona = { id: 'persona-id', type: 'persona', name: 'uph-show' };
const binding = {
  user: toContextOption(user),
  persona: toContextOption(persona),
};

describe('term Persona form values', () => {
  it('retains entity identities for a default and user override', () => {
    expect(
      toContextBindings({
        contextPersona: toContextOption(persona),
        overrides: [binding],
      })
    ).toEqual({
      contextPersona: persona,
      contextPersonaOverrides: [{ user, persona }],
    });
  });

  it('clears the default and all user overrides', () => {
    expect(toContextBindings({ contextPersona: null, overrides: [] })).toEqual({
      contextPersona: undefined,
      contextPersonaOverrides: [],
    });
  });

  it('rejects duplicate user identities regardless of display name', () => {
    expect(
      validateContextBindings([
        binding,
        {
          ...binding,
          user: toContextOption({ ...user, name: 'Alice renamed' }),
        },
      ])
    ).toBe('message.term-context-duplicate-user');
  });

  it('rejects an incomplete override', () => {
    expect(
      validateContextBindings([{ user: binding.user, persona: null }])
    ).toBe('message.term-context-incomplete-override');
  });

  it('allows distinct users to reuse the same Persona', () => {
    expect(
      validateContextBindings([
        binding,
        { ...binding, user: toContextOption({ ...user, id: 'another-user' }) },
      ])
    ).toBeUndefined();
  });
});
