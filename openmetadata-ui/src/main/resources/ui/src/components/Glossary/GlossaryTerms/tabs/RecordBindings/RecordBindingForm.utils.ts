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
  type CreateGlossaryRecordBinding,
  type RecordBusinessKey,
  type RecordLocator,
  type RecordLocatorKey,
  RecordLocatorType,
  RecordLocatorValueType,
} from '../../../../../generated/api/data/createGlossaryRecordBinding';

export type RecordLocatorValue = boolean | number | string;

export type RecordLocatorValueTypeFormValue =
  | 'BOOLEAN'
  | 'DATE'
  | 'DATETIME'
  | 'NUMBER'
  | 'STRING';

export interface RecordBindingAssetFormData {
  id: string;
  type: string;
}

export interface RecordLocatorKeyFormData {
  fieldFqn: string;
  value: RecordLocatorValue;
  valueType: RecordLocatorValueTypeFormValue;
}

export interface RecordBusinessKeyFormData {
  jsonPointer: string;
  value: RecordLocatorValue;
  valueType: RecordLocatorValueTypeFormValue;
}

export interface RecordPathParameterFormData {
  key: string;
  value: string;
}

export interface RecordBindingFormData {
  asset: RecordBindingAssetFormData;
  auth?: unknown;
  businessKey?: RecordBusinessKeyFormData;
  displayName?: string;
  environment?: string;
  header?: unknown;
  headers?: unknown;
  keys?: RecordLocatorKeyFormData[];
  locatorHash?: unknown;
  pathParameters?: RecordPathParameterFormData[];
  token?: unknown;
}

const RECORD_LOCATOR_VALUE_TYPES: Record<
  RecordLocatorValueTypeFormValue,
  RecordLocatorValueType
> = {
  BOOLEAN: RecordLocatorValueType.Boolean,
  DATE: RecordLocatorValueType.Date,
  DATETIME: RecordLocatorValueType.Datetime,
  NUMBER: RecordLocatorValueType.Number,
  STRING: RecordLocatorValueType.String,
};

const transformKey = (key: RecordLocatorKeyFormData): RecordLocatorKey => ({
  fieldFqn: key.fieldFqn,
  value: key.value,
  valueType: RECORD_LOCATOR_VALUE_TYPES[key.valueType],
});

const transformBusinessKey = (
  businessKey: RecordBusinessKeyFormData
): RecordBusinessKey => ({
  jsonPointer: businessKey.jsonPointer,
  value: businessKey.value,
  valueType: RECORD_LOCATOR_VALUE_TYPES[businessKey.valueType],
});

export const transformRecordBindingFormData = (
  formData: RecordBindingFormData
): CreateGlossaryRecordBinding => {
  const asset = {
    id: formData.asset.id,
    type: formData.asset.type,
  };
  const displayName =
    formData.displayName === undefined
      ? {}
      : { displayName: formData.displayName };

  if (formData.asset.type === 'table') {
    return {
      asset,
      ...displayName,
      locator: { keys: (formData.keys ?? []).map(transformKey) },
      locatorType: RecordLocatorType.TablePrimaryKey,
    };
  }

  const locator: RecordLocator = {};
  if (formData.environment !== undefined) {
    locator.environment = formData.environment;
  }
  if (formData.pathParameters !== undefined) {
    locator.pathParameters = Object.fromEntries(
      formData.pathParameters.map(({ key, value }) => [key, value])
    );
  }
  if (formData.businessKey !== undefined) {
    locator.businessKey = transformBusinessKey(formData.businessKey);
  }

  return {
    asset,
    ...displayName,
    locator,
    locatorType: RecordLocatorType.APIResource,
  };
};
