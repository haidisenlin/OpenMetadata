/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 *  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */

import { transformRecordBindingFormData } from './RecordBindingForm.utils';

describe('RecordBinding form mapping', () => {
  it('FR-003 AC-003 builds a table-primary-key payload and drops API fields', () => {
    const result = transformRecordBindingFormData({
      asset: { id: 'table-id', type: 'table' },
      displayName: 'Equipment A',
      keys: [{ fieldFqn: 'service.db.schema.equipment.id', valueType: 'STRING', value: 'A' }],
      environment: 'must-not-leak',
      pathParameters: [{ key: 'token', value: 'must-not-leak' }],
      businessKey: { jsonPointer: '/id', valueType: 'STRING', value: 'must-not-leak' },
    });

    expect(result).toEqual({
      asset: { id: 'table-id', type: 'table' },
      displayName: 'Equipment A',
      locatorType: 'TABLE_PRIMARY_KEY',
      locator: {
        keys: [{ fieldFqn: 'service.db.schema.equipment.id', valueType: 'STRING', value: 'A' }],
      },
    });
  });

  it('FR-003 SEC-001 builds an API payload from an allowlist', () => {
    const result = transformRecordBindingFormData({
      asset: { id: 'endpoint-id', type: 'apiEndpoint' },
      keys: [{ fieldFqn: 'must-not-leak', valueType: 'STRING', value: 'secret' }],
      environment: 'prod',
      pathParameters: [{ key: 'equipmentId', value: 'A' }],
      businessKey: { jsonPointer: '/workReportId', valueType: 'STRING', value: 'WR-1' },
      token: 'must-not-leak',
      locatorHash: 'must-not-leak',
    });

    expect(result).toEqual({
      asset: { id: 'endpoint-id', type: 'apiEndpoint' },
      locatorType: 'API_RESOURCE',
      locator: {
        environment: 'prod',
        pathParameters: { equipmentId: 'A' },
        businessKey: { jsonPointer: '/workReportId', valueType: 'STRING', value: 'WR-1' },
      },
    });
    expect(JSON.stringify(result)).not.toMatch(/token|locatorHash/i);
  });

  it('preserves NUMBER values supplied as precise decimal strings', () => {
    const result = transformRecordBindingFormData({
      asset: { id: 'table-id', type: 'table' },
      keys: [
        {
          fieldFqn: 'service.db.schema.equipment.id',
          valueType: 'NUMBER',
          value: '9007199254740993.0',
        },
      ],
    });

    expect(result.locator.keys?.[0].value).toBe('9007199254740993.0');
  });
});
