/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

import { render, waitFor } from '@testing-library/react';
import { GlossaryTerm } from '../../../../../generated/entity/data/glossaryTerm';
import { RecordLocatorType } from '../../../../../generated/type/recordBinding';
import { getGlossaryTermRecordBindings } from '../../../../../rest/glossaryAPI';
import RecordBindings from './RecordBindings';

const mockContext = {
  data: {
    id: 'term-id',
    name: 'EquipmentA',
  } as GlossaryTerm,
  permissions: {
    EditGlossaryTerms: true,
  },
};

jest.mock('../../../../Customization/GenericProvider/GenericContext', () => ({
  useGenericContext: jest.fn(() => mockContext),
}));

jest.mock('../../../../../rest/glossaryAPI', () => ({
  createGlossaryTermRecordBinding: jest.fn(),
  deleteGlossaryTermRecordBinding: jest.fn(),
  getGlossaryTermRecordBindings: jest.fn(),
}));

jest.mock('./RecordBindingForm', () => ({
  __esModule: true,
  default: () => null,
}));

describe('RecordBindings', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockContext.permissions.EditGlossaryTerms = true;
    (getGlossaryTermRecordBindings as jest.Mock).mockResolvedValue({
      data: [
        {
          id: 'binding-id',
          asset: { id: 'table-id', type: 'table', name: 'equipment' },
          term: { id: 'term-id', type: 'glossaryTerm' },
          displayName: 'Equipment A',
          locatorType: RecordLocatorType.TablePrimaryKey,
          locator: {
            keys: [
              {
                fieldFqn: 'service.db.schema.equipment.id',
                valueType: 'STRING',
                value: 'A',
              },
            ],
          },
          locatorHash:
            '036c2475da237089700111427d25b2021dbd02131f5f55a92c9f76f19656d061',
          status: 'UNVERIFIED',
          createdAt: 1,
          createdBy: 'admin',
          updatedAt: 1,
          updatedBy: 'admin',
        },
      ],
      paging: { limit: 15, offset: 0, total: 1 },
    });
  });

  it('loads and renders record bindings without exposing locator hashes', async () => {
    const { findByText, queryByText } = render(<RecordBindings />);

    await waitFor(() =>
      expect(getGlossaryTermRecordBindings).toHaveBeenCalledWith('term-id', {
        limit: 15,
        offset: 0,
      })
    );

    expect(await findByText('Equipment A')).toBeInTheDocument();
    expect(
      queryByText(
        '036c2475da237089700111427d25b2021dbd02131f5f55a92c9f76f19656d061'
      )
    ).not.toBeInTheDocument();
  });

  it('hides the add action without glossary-term edit permission', async () => {
    mockContext.permissions.EditGlossaryTerms = false;
    const { queryByTestId } = render(<RecordBindings />);

    await waitFor(() => expect(getGlossaryTermRecordBindings).toHaveBeenCalled());

    expect(queryByTestId('add-record-binding')).not.toBeInTheDocument();
  });
});
