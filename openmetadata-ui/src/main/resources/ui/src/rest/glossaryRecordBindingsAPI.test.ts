/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

import APIClient from '.';
import {
  createGlossaryTermRecordBinding,
  deleteGlossaryTermRecordBinding,
  getGlossaryTermRecordBindings,
} from './glossaryAPI';

jest.mock('.');

describe('glossary record bindings API', () => {
  beforeEach(() => jest.clearAllMocks());

  it('FR-003 AC-001 lists bindings with offset pagination', async () => {
    const response = { data: { data: [], paging: { limit: 15, offset: 15, total: 0 } } };
    (APIClient.get as jest.Mock).mockResolvedValueOnce(response);

    await expect(
      getGlossaryTermRecordBindings('term-id', { limit: 15, offset: 15 })
    ).resolves.toEqual(response.data);
    expect(APIClient.get).toHaveBeenCalledWith('/glossaryTerms/term-id/recordBindings', {
      params: { limit: 15, offset: 15 },
    });
  });

  it('FR-003 AC-002 creates and deletes a scoped binding', async () => {
    const request = {
      asset: { id: 'table-id', type: 'table' },
      locatorType: 'TABLE_PRIMARY_KEY',
      locator: { keys: [{ fieldFqn: 'service.db.schema.table.id', valueType: 'STRING', value: 'A' }] },
    };
    const binding = { id: 'binding-id', ...request };
    (APIClient.post as jest.Mock).mockResolvedValueOnce({ data: binding });
    (APIClient.delete as jest.Mock).mockResolvedValueOnce({});

    await expect(createGlossaryTermRecordBinding('term-id', request)).resolves.toEqual(binding);
    await deleteGlossaryTermRecordBinding('term-id', 'binding-id');

    expect(APIClient.post).toHaveBeenCalledWith('/glossaryTerms/term-id/recordBindings', request);
    expect(APIClient.delete).toHaveBeenCalledWith(
      '/glossaryTerms/term-id/recordBindings/binding-id'
    );
  });
});
