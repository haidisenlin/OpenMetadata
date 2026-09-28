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
} from '@testing-library/react';
import { GlossaryTerm } from '../../../../generated/entity/data/glossaryTerm';
import { showErrorToast } from '../../../../utils/ToastUtils';
import TermPersonaContext from './TermPersonaContext';

const persona = { id: 'persona-id', type: 'persona', name: 'uph-show' };
const user = { id: 'user-id', type: 'user', name: 'Alice' };
const term: GlossaryTerm = {
  id: 'term-id',
  name: 'UPH',
  description: 'Units per hour',
  glossary: { id: 'glossary-id', type: 'glossary' },
  contextPersona: persona,
  contextPersonaOverrides: [
    { user, persona: { ...persona, id: 'detail-id', name: 'uph-detail' } },
  ],
};
const mockContext = {
  data: term,
  permissions: { EditAll: true },
  isVersionView: false,
  onUpdate: jest.fn(),
};

jest.mock('../../../Customization/GenericProvider/GenericContext', () => ({
  useGenericContext: () => mockContext,
}));
jest.mock('../../../../rest/PersonaAPI', () => ({
  searchPersonas: jest.fn(async () => [persona]),
}));
jest.mock('../../../../rest/userAPI', () => ({
  getUsers: jest.fn(async () => ({ data: [user] })),
}));
jest.mock('../../../../rest/searchAPI', () => ({
  searchQuery: jest.fn().mockResolvedValue({ hits: { hits: [] } }),
}));
jest.mock('../../../../utils/ToastUtils', () => ({
  showErrorToast: jest.fn(),
}));

describe('TermPersonaContext', () => {
  beforeEach(() => {
    jest.useRealTimers();
    mockContext.data = term;
    mockContext.permissions.EditAll = true;
    mockContext.isVersionView = false;
    mockContext.onUpdate.mockResolvedValue(undefined);
  });

  it('shows the default Persona and the per-user bindings', () => {
    render(<TermPersonaContext />);

    expect(screen.getByText('uph-show')).toBeVisible();
    expect(screen.getByText('Alice')).toBeVisible();
    expect(screen.getByText('uph-detail')).toBeVisible();
  });

  it.each([
    { canEdit: false, isVersion: false },
    { canEdit: true, isVersion: true },
  ])('protects read-only views: %o', ({ canEdit, isVersion }) => {
    mockContext.permissions.EditAll = canEdit;
    mockContext.isVersionView = isVersion;
    render(<TermPersonaContext />);

    expect(
      screen.queryByTestId('edit-term-persona-context')
    ).not.toBeInTheDocument();
  });

  it('clears both bindings and preserves unrelated term fields when saved', async () => {
    render(<TermPersonaContext />);
    fireEvent.click(screen.getByTestId('edit-term-persona-context'));
    fireEvent.click(screen.getByTestId('clear-default-context-persona'));
    fireEvent.click(screen.getByTestId('remove-context-override-0'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('save-term-persona-context'));
    });
    await waitFor(() =>
      expect(mockContext.onUpdate).toHaveBeenCalledWith({
        ...term,
        contextPersona: undefined,
        contextPersonaOverrides: [],
      })
    );
    await waitFor(() => {
      expect(showErrorToast).not.toHaveBeenCalled();
      expect(
        screen.queryByTestId('save-term-persona-context')
      ).not.toBeInTheDocument();
    });
  });

  it('keeps edits available after a failed save', async () => {
    const error = new Error('Denied');
    mockContext.onUpdate.mockRejectedValue(error);
    render(<TermPersonaContext />);
    fireEvent.click(screen.getByTestId('edit-term-persona-context'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('save-term-persona-context'));
    });
    await waitFor(() => expect(showErrorToast).toHaveBeenCalledWith(error));

    expect(screen.getByTestId('save-term-persona-context')).toBeVisible();
  });

  it('cancels edits without persisting a change', () => {
    render(<TermPersonaContext />);
    fireEvent.click(screen.getByTestId('edit-term-persona-context'));
    fireEvent.click(screen.getByTestId('clear-default-context-persona'));
    fireEvent.click(screen.getByRole('button', { name: 'label.cancel' }));

    expect(screen.getByText('uph-show')).toBeVisible();
    expect(mockContext.onUpdate).not.toHaveBeenCalled();
  });

  it('requires both fields when adding a user override', async () => {
    render(<TermPersonaContext />);
    fireEvent.click(screen.getByTestId('edit-term-persona-context'));
    fireEvent.click(screen.getByTestId('add-context-override'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('save-term-persona-context'));
    });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'message.term-context-incomplete-override'
    );
    expect(mockContext.onUpdate).not.toHaveBeenCalled();
  });

  it('rejects duplicate users before saving', async () => {
    mockContext.data = {
      ...term,
      contextPersonaOverrides: [
        ...(term.contextPersonaOverrides ?? []),
        { user, persona },
      ],
    };
    render(<TermPersonaContext />);
    fireEvent.click(screen.getByTestId('edit-term-persona-context'));
    await act(async () => {
      fireEvent.click(screen.getByTestId('save-term-persona-context'));
    });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'message.term-context-duplicate-user'
    );
    expect(mockContext.onUpdate).not.toHaveBeenCalled();
  });

  it('shows an empty configuration without a binding', () => {
    mockContext.data = {
      ...term,
      contextPersona: undefined,
      contextPersonaOverrides: [],
    };
    render(<TermPersonaContext />);

    expect(screen.getByText('label.not-set')).toBeVisible();
  });
});
