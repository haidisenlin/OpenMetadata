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
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { GlossaryTerm } from '../../../../generated/entity/data/glossaryTerm';
import { searchPersonas } from '../../../../rest/PersonaAPI';
import { searchQuery } from '../../../../rest/searchAPI';
import { getUsers } from '../../../../rest/userAPI';
import { showErrorToast } from '../../../../utils/ToastUtils';
import TermPersonaContextForm from './TermPersonaContextForm';

jest.mock('../../../../rest/PersonaAPI', () => ({ searchPersonas: jest.fn() }));
jest.mock('../../../../rest/userAPI', () => ({ getUsers: jest.fn() }));
jest.mock('../../../../rest/searchAPI', () => ({ searchQuery: jest.fn() }));

const term: GlossaryTerm = {
  id: 'term-id',
  name: 'UPH',
  description: 'Units per hour',
  glossary: { id: 'glossary-id', type: 'glossary' },
};
const persona = { id: 'persona-id', type: 'persona', name: 'uph-show' };
const user = {
  id: 'user-id',
  type: 'user',
  name: 'Alice',
  displayName: 'Alice',
  email: 'alice@example.com',
};
const onSave = jest.fn();

const choose = async (
  input: HTMLElement,
  query: string,
  optionName: string
) => {
  await userEvent.click(input);
  await userEvent.type(input, query);
  await userEvent.click(
    await screen.findByRole('option', { name: optionName })
  );
  await userEvent.keyboard('{Escape}');
};

describe('TermPersonaContextForm', () => {
  beforeEach(() => {
    jest.useRealTimers();
    (searchPersonas as jest.Mock).mockResolvedValue([persona]);
    (getUsers as jest.Mock).mockResolvedValue({
      data: [user],
      paging: { total: 1 },
    });
    (searchQuery as jest.Mock).mockResolvedValue({
      hits: { hits: [{ _source: user }], total: { value: 1 } },
    });
    onSave.mockResolvedValue(undefined);
  });

  it('selects a default and user Persona and saves their UUIDs', async () => {
    render(
      <TermPersonaContextForm
        term={term}
        onCancel={jest.fn()}
        onSave={onSave}
      />
    );
    await choose(
      screen.getByRole('combobox', { name: 'label.default-persona' }),
      'uph',
      'uph-show'
    );
    fireEvent.click(screen.getByTestId('add-context-override'));
    const row = within(screen.getByTestId('context-override-0'));
    await choose(
      row.getByRole('combobox', { name: 'label.user' }),
      'Ali',
      'Alice'
    );
    await choose(
      row.getByRole('combobox', { name: 'label.persona' }),
      'uph',
      'uph-show'
    );
    await act(async () => {
      fireEvent.click(screen.getByTestId('save-term-persona-context'));
    });
    await waitFor(() =>
      expect(onSave).toHaveBeenCalledWith(
        expect.objectContaining({
          contextPersona: expect.objectContaining({ id: persona.id }),
          contextPersonaOverrides: [
            {
              user: expect.objectContaining({ id: user.id }),
              persona: expect.objectContaining({ id: persona.id }),
            },
          ],
        })
      )
    );
  });

  it('searches Personas on the server as the query changes', async () => {
    render(
      <TermPersonaContextForm
        term={term}
        onCancel={jest.fn()}
        onSave={onSave}
      />
    );
    const input = screen.getByRole('combobox', {
      name: 'label.default-persona',
    });
    await userEvent.click(input);
    await userEvent.type(input, 'uph');
    await waitFor(() => expect(searchPersonas).toHaveBeenCalledWith('uph', 25));
  });

  it('surfaces failed searches without discarding existing selections', async () => {
    const error = new Error('Search unavailable');
    (searchPersonas as jest.Mock).mockRejectedValue(error);
    render(
      <TermPersonaContextForm
        term={{ ...term, contextPersona: persona }}
        onCancel={jest.fn()}
        onSave={onSave}
      />
    );
    await waitFor(() => expect(showErrorToast).toHaveBeenCalledWith(error));

    expect(screen.getByText('uph-show')).toBeVisible();
  });
});
