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
import { isDecimalNumber, isWholeNumber } from "../numberText";
import { isNode, type SerializedNode } from "../serializedNode";

// What a field holds: text, a whole number, a number, a boolean, or a reference to another node.
export type FieldKind = "text" | "long" | "double" | "boolean" | "reference";

export interface FieldChoice {
  value: string;
  label: string;
}

// A field that an update event may change, as the `fields` serialization lists it in `@fields`
// (see updateContent in docs/workflows.md). The server checks a patch against the same rules, and
// the checks here spare a request it would refuse.
export interface ContentField {
  // The property name
  name: string;
  label: string;
  kind: FieldKind;
  // Whether it holds a list of values
  multiple: boolean;
  mandatory: boolean;
  // Whether its text is edited over several lines
  multiline: boolean;
  // A short explanation shown under the input
  help?: string;
  // For a reference, the `sling:resourceType` of the nodes it may point at; any path if absent
  referenceType?: string;
  // For a reference, the path the nodes it may point at are under; anywhere if absent
  referenceRoot?: string;
  // The values the field may take; any value of its kind if absent
  choices?: FieldChoice[];
  // Applies only while that property holds one of these values; an empty property name applies nowhere
  appliesWhen?: { property: string; values: string[] };
  // What content of its type starts with, which is what it holds when it is not set
  default?: string | number | boolean | (string | number | boolean)[];
}

// A type of content a create event may add inside a node, as the `creatable` serialization lists it
// in `@creatable`.
export interface CreatableType {
  // The node type name, such as `sch:Question`
  type: string;
  label: string;
  // The fields to fill in for new content of the type
  fields: ContentField[];
  // The name new content takes when its text makes none it may take; undefined if there is none
  defaultName?: string;
  // Whether a create event may ask for a name; false when the server always chooses it
  named: boolean;
  // A regular expression that a whole name must match; any valid name if absent
  namePattern?: string;
  // What namePattern asks for, in words
  nameHint?: string;
}

export type { SerializedNode };

// A field's value as it is edited: text for one value, numbers included, a list of texts for several,
// and a boolean for a single boolean field
export type FieldValue = string | string[] | boolean;

export type FieldValues = Record<string, FieldValue>;

// A field's value in a patch; null removes it
export type PatchValue = string | number | boolean | (string | number | boolean)[] | null;

const KINDS: readonly string[] = [ "text", "long", "double", "boolean", "reference" ];


const isField = (value: unknown): value is ContentField =>
  isNode(value) && typeof value.name === "string" && typeof value.label === "string"
    && typeof value.kind === "string" && KINDS.includes(value.kind);

export const fieldsOf = (node: SerializedNode): ContentField[] =>
  Array.isArray(node["@fields"]) ? node["@fields"].filter(isField) : [];

const optionalText = (value: unknown): string | undefined => (typeof value === "string" ? value : undefined);

const isCreatable = (value: unknown): value is Record<string, unknown> & { type: string; label: string } =>
  isNode(value) && typeof value.type === "string" && typeof value.label === "string";

export const creatableOf = (node: SerializedNode): CreatableType[] =>
  Array.isArray(node["@creatable"])
    ? node["@creatable"].filter(isCreatable)
      .map(entry => ({
        type: entry.type,
        label: entry.label,
        fields: fieldsOf({ "@fields": entry.fields }),
        defaultName: optionalText(entry.defaultName),
        named: entry.named !== false,
        namePattern: optionalText(entry.namePattern),
        nameHint: optionalText(entry.nameHint),
      }))
    : [];

// New content of a type, for FieldsDialog to fill in: no values yet, and the type's fields
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
  return isNode(value) && typeof value["@path"] === "string" ? value["@path"] : undefined;
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

// Whether a field applies. The property it depends on is read from the values being entered when it
// is one of the fields, or else from the stored node. The server applies the same rule to the
// patched node.
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
    return isWholeNumber(text) ? Number(text) : undefined;
  }
  if (field.kind === "double") {
    return isDecimalNumber(text) ? Number(text) : undefined;
  }
  return text;
}

// A field's entered value as the patch gives it: null when it is empty, and undefined when some
// part of it is not of the field's kind
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

// What saving sends: the applicable fields whose value changed, and null for one emptied. A field
// that stops applying is left out, and the server removes its value itself.
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

// The JCR-SQL2 query for the nodes a reference field may point at: the nodes of its type, under its
// root. Undefined when it names no type, since any path may then be entered.
export function referenceQuery(field: Pick<ContentField, "referenceType" | "referenceRoot">): string | undefined {
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
  // The node's `jcr:uuid`, if it has one, which a reference may store instead of the path
  identifier?: string;
}

// A node a reference may point at, labelled by its title or label, or else by its path under the
// root; undefined for a node without a path
export function candidateOf(node: SerializedNode, referenceRoot?: string): ReferenceCandidate | undefined {
  const path = node["@path"];
  if (typeof path !== "string") {
    return undefined;
  }
  const named = [ node.title, node.label ].find(name => typeof name === "string" && name.trim() !== "");
  const root = referenceRoot?.replace(/\/*$/, "/");
  const relative = root && path.startsWith(root) ? path.substring(root.length) : path;
  const identifier = node["jcr:uuid"];
  return { path, label: typeof named === "string" ? named : relative,
    ...typeof identifier === "string" ? { identifier } : {} };
}
