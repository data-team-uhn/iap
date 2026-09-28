/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { escapeJQL } from "../escape";

// A field an update would change, as the `fields` serialization describes it (see updateContent in
// docs/workflows.md). The server holds a patch to the same rules, so these only save a refused request.
export type FieldKind = "text" | "long" | "double" | "boolean" | "reference";

export interface FieldChoice {
  value: string;
  label: string;
}

export interface ContentField {
  name: string;
  label: string;
  kind: FieldKind;
  multiple: boolean;
  mandatory: boolean;
  multiline: boolean;
  help?: string;
  referenceType?: string;
  referenceRoot?: string;
  choices?: FieldChoice[];
  // Applies only while that property holds one of these values; an empty property applies nowhere
  appliesWhen?: { property: string; values: string[] };
  // What content of its type starts with, which is what it holds when it is not set
  default?: string | number | boolean | (string | number | boolean)[];
}

// A type of content a create event would add inside a node, as the `creatable` serialization describes it
export interface CreatableType {
  type: string;
  label: string;
  fields: ContentField[];
}

export type SerializedNode = Record<string, unknown>;

// What a field is edited as: text for one value, numbers as typed, a list for several, a switch for true or false
export type FieldValue = string | string[] | boolean;

export type FieldValues = Record<string, FieldValue>;

// What a patch gives a field; null removes it
export type PatchValue = string | number | boolean | (string | number | boolean)[] | null;

const KINDS: readonly string[] = [ "text", "long", "double", "boolean", "reference" ];

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null;

const isField = (value: unknown): value is ContentField =>
  isObject(value) && typeof value.name === "string" && typeof value.label === "string"
    && typeof value.kind === "string" && KINDS.includes(value.kind);

export const fieldsOf = (node: SerializedNode): ContentField[] =>
  Array.isArray(node["@fields"]) ? node["@fields"].filter(isField) : [];

const isCreatable = (value: unknown): value is Record<string, unknown> & { type: string; label: string } =>
  isObject(value) && typeof value.type === "string" && typeof value.label === "string";

export const creatableOf = (node: SerializedNode): CreatableType[] =>
  Array.isArray(node["@creatable"])
    ? node["@creatable"].filter(isCreatable)
      .map(entry => ({ type: entry.type, label: entry.label, fields: fieldsOf({ "@fields": entry.fields }) }))
    : [];

// What FieldsDialog edits to fill in new content of a type: nothing yet, and the fields it starts with
export const newContentOf = (type: CreatableType): SerializedNode => ({ "@fields": type.fields });

const isSwitch = (field: ContentField): boolean => field.kind === "boolean" && !field.multiple;

// A stored value as text: a reference is serialized as the node it points at, or as its path
function textOf(value: unknown): string | undefined {
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return isObject(value) && typeof value["@path"] === "string" ? value["@path"] : undefined;
}

const textsOf = (value: unknown): string[] => {
  const values: unknown[] = Array.isArray(value) ? value : [ value ];
  return values.map(textOf).filter((text): text is string => text !== undefined);
};

const storedTexts = (node: SerializedNode, name: string): string[] => textsOf(node[name]);

export const initialValue = (node: SerializedNode, field: ContentField): FieldValue => {
  const value = node[field.name] ?? field.default;
  if (isSwitch(field)) {
    return value === true;
  }
  const stored = textsOf(value);
  return field.multiple ? stored : stored[0] ?? "";
};

export const initialValues = (node: SerializedNode, fields: ContentField[]): FieldValues =>
  Object.fromEntries(fields.map(field => [ field.name, initialValue(node, field) ]));

const entered = (value: FieldValue): string[] => {
  if (typeof value === "boolean") {
    return [ String(value) ];
  }
  return (Array.isArray(value) ? value : [ value ]).map(text => text.trim()).filter(text => text !== "");
};

// Whether a field applies, judged on the value being entered for the property it depends on, if that is
// edited alongside it, or else on the stored one: the same rule the server applies to the patched node
export function applies(field: ContentField, fields: ContentField[], values: FieldValues, node: SerializedNode) {
  const rule = field.appliesWhen;
  if (!rule) {
    return true;
  }
  const edited = fields.some(other => other.name === rule.property);
  const current = edited ? entered(values[rule.property]) : storedTexts(node, rule.property);
  return rule.property !== "" && current.some(value => rule.values.includes(value));
}

// One entered value as the patch gives it, or undefined when it is not one of the field's kind
function parse(field: ContentField, text: string): string | number | boolean | undefined {
  if (field.kind === "boolean") {
    return text === "true" || text === "false" ? text === "true" : undefined;
  }
  if (field.kind === "long") {
    return /^[-+]?\d+$/.test(text) && Number.isSafeInteger(Number(text)) ? Number(text) : undefined;
  }
  if (field.kind === "double") {
    return Number.isFinite(Number(text)) ? Number(text) : undefined;
  }
  return text;
}

// What the patch gives a field for a value: undefined when some part of it is not valid
export function patchValueOf(field: ContentField, value: FieldValue): PatchValue | undefined {
  if (typeof value === "boolean") {
    return value;
  }
  const parsed = entered(value).map(text => parse(field, text));
  if (parsed.some(item => item === undefined)) {
    return undefined;
  }
  const values = parsed as (string | number | boolean)[];
  if (values.length === 0) {
    return null;
  }
  return field.multiple ? values : values[0];
}

export const isValid = (field: ContentField, value: FieldValue): boolean => patchValueOf(field, value) !== undefined;

// The fields to show: the ones that apply to what is being entered
export const applicableFields = (fields: ContentField[], values: FieldValues, node: SerializedNode): ContentField[] =>
  fields.filter(field => applies(field, fields, values, node));

// What saving sends: the applicable fields whose value changed, a cleared one as null. Fields that stop
// applying are left out, since the server removes them itself.
export function changesOf(fields: ContentField[], values: FieldValues, node: SerializedNode) {
  const changes: Record<string, PatchValue> = {};
  for (const field of applicableFields(fields, values, node)) {
    const after = patchValueOf(field, values[field.name]);
    const before = patchValueOf(field, initialValue(node, field));
    if (after !== undefined && JSON.stringify(after) !== JSON.stringify(before)) {
      changes[field.name] = after;
    }
  }
  return changes;
}

// The applicable fields that cannot be saved as they are: a mandatory one left empty, or a value not of the kind
export const blockingFields = (fields: ContentField[], values: FieldValues, node: SerializedNode): ContentField[] =>
  applicableFields(fields, values, node).filter(field => {
    const value = patchValueOf(field, values[field.name]);
    return value === undefined || field.mandatory && value === null;
  });

// Where a reference field's candidates come from: the nodes of its type, under its root. Undefined when it
// names no type, and any path may be entered.
export function referenceQuery(field: ContentField): string | undefined {
  if (!field.referenceType) {
    return undefined;
  }
  const root = field.referenceRoot?.replace(/(.)\/+$/, "$1");
  return `select * from [nt:base] as n where n.[sling:resourceType] = '${escapeJQL(field.referenceType)}'`
    + (root ? ` and isdescendantnode(n, '${escapeJQL(root)}')` : "");
}

export interface ReferenceCandidate {
  path: string;
  label: string;
}

// A node a reference may point at, as it is offered: by its title or label, else by its path under the root
export function candidateOf(node: SerializedNode, referenceRoot?: string): ReferenceCandidate | undefined {
  const path = node["@path"];
  if (typeof path !== "string") {
    return undefined;
  }
  const named = [ node.title, node.label ].find(name => typeof name === "string" && name.trim() !== "");
  const root = referenceRoot?.replace(/\/*$/, "/");
  const relative = root && path.startsWith(root) ? path.substring(root.length) : path;
  return { path, label: typeof named === "string" ? named : relative };
}
