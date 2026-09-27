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
/**
 * This schema defines a record-level binding between a glossary term and a data asset.
 */
export interface RecordBinding {
    /**
     * Data asset containing the record.
     */
    asset: EntityReference;
    /**
     * Creation time in Unix epoch time milliseconds.
     */
    createdAt: number;
    /**
     * User that created the record binding.
     */
    createdBy: string;
    /**
     * Display name for the bound record.
     */
    displayName?: string;
    /**
     * Unique identifier of the record binding.
     */
    id: string;
    /**
     * Stable values used to locate a record within an asset.
     */
    locator: RecordLocator;
    /**
     * Hash of the canonical record locator.
     */
    locatorHash: string;
    /**
     * Strategy used to locate the bound record.
     */
    locatorType: RecordLocatorType;
    /**
     * Verification state of a record binding.
     */
    status: RecordBindingStatus;
    /**
     * Glossary term bound to the record.
     */
    term: EntityReference;
    /**
     * Last update time in Unix epoch time milliseconds.
     */
    updatedAt: number;
    /**
     * User that last updated the record binding.
     */
    updatedBy: string;
}

/**
 * Data asset containing the record.
 *
 * This schema defines the EntityReference type used for referencing an entity.
 * EntityReference is used for capturing relationships from one entity to another. For
 * example, a table has an attribute called database of type EntityReference that captures
 * the relationship of a table `belongs to a` database.
 *
 * Glossary term bound to the record.
 */
export interface EntityReference {
    /**
     * If true the entity referred to has been soft-deleted.
     */
    deleted?: boolean;
    /**
     * Optional description of entity.
     */
    description?: string;
    /**
     * Display Name that identifies this entity.
     */
    displayName?: string;
    /**
     * Fully qualified name of the entity instance.
     */
    fullyQualifiedName?: string;
    /**
     * Link to the entity resource.
     */
    href?: string;
    /**
     * Unique identifier that identifies an entity instance.
     */
    id: string;
    /**
     * If true the relationship indicated by this entity reference is inherited from the parent
     * entity.
     */
    inherited?: boolean;
    /**
     * Name of the entity instance.
     */
    name?: string;
    /**
     * Entity type/class name.
     */
    type: string;
}

/**
 * Stable values used to locate a record within an asset.
 */
export interface RecordLocator {
    /**
     * A value extracted from an API resource using a JSON Pointer.
     */
    businessKey?: RecordBusinessKey;
    /**
     * Environment that contains the API resource.
     */
    environment?: string;
    /**
     * Fields and values that form a table record key.
     */
    keys?: RecordLocatorKey[];
    /**
     * Path parameter values used to address the API resource.
     */
    pathParameters?: { [key: string]: string };
}

/**
 * A value extracted from an API resource using a JSON Pointer.
 */
export interface RecordBusinessKey {
    /**
     * JSON Pointer identifying the business key in the API resource.
     */
    jsonPointer: string;
    /**
     * Typed business key value.
     */
    value: boolean | number | string;
    /**
     * Logical type of a record locator value.
     */
    valueType: RecordLocatorValueType;
}

/**
 * A table field and value used to locate a record.
 */
export interface RecordLocatorKey {
    /**
     * Fully qualified name of the field used as a record key.
     */
    fieldFqn: string;
    /**
     * Typed record key value.
     */
    value: boolean | number | string;
    /**
     * Logical type of a record locator value.
     */
    valueType: RecordLocatorValueType;
}

/**
 * Verification state of a record binding.
 */
export enum RecordBindingStatus {
    Stale = "STALE",
    Unverified = "UNVERIFIED",
    Verified = "VERIFIED",
}

/**
 * Strategy used to locate the bound record.
 */
export enum RecordLocatorType {
    APIResource = "API_RESOURCE",
    TablePrimaryKey = "TABLE_PRIMARY_KEY",
}

/**
 * Logical type of a record locator value.
 */
export enum RecordLocatorValueType {
    Boolean = "BOOLEAN",
    Date = "DATE",
    Datetime = "DATETIME",
    Number = "NUMBER",
    String = "STRING",
}
