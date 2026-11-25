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

// The vocabulary of workflow node kinds, as the server documents it, and the rule that says which entry
// a drawn BPMN element means. The rule is the editor's copy of the server parser's, and the two have to
// resolve the same element to the same entry.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { readNode } from "@iap/frontend-commons/useNode";

import { bpmnChildren, capitalised, elementName, iapExtensionNames, localName, text } from "./moddle";

import type { ModdleElement } from "./moddle";

export const WORKFLOW_TYPES_PATH = "/WorkflowTypes";

// One choice offered for a parameter, such as `{ value: "draft", label: "Draft" }`.
export interface ParameterOption {
  value: string;
  label: string;
}

// Where a diagram writes a parameter's value. The rules are in bpmnParameters.
export type XmlLocation = "extensionElement" | "attribute" | "bpmnAttribute" | "bpmnElement";

const XML_LOCATIONS: readonly XmlLocation[] = [ "extensionElement", "attribute", "bpmnAttribute", "bpmnElement" ];

// One value a flow node of some kind carries.
export interface Parameter {
  name: string;
  label: string;
  description?: string;
  // The type of value: `text`, `boolean`, `long`, `reference` (a node of the type `referenceType` names),
  // `principal` (a user or group name) or `structure` (an element holding the fields in `parameters`).
  dataType: string;
  // The fewest values required, such as 1 for a mandatory parameter.
  minValues: number;
  // The most values accepted. Zero means any number.
  maxValues: number;
  xmlLocation: XmlLocation;
  // The local names of the BPMN child elements leading to the one holding the value, such as
  // `["timerEventDefinition"]`. Empty when the flow node holds it itself.
  xmlPath: string[];
  // The node type a reference points at, such as `"wf/WorkflowVersion"`.
  referenceType?: string;
  options: ParameterOption[];
  // The fields of a structure.
  parameters?: Parameter[];
  // The field of a repeated structure whose value names each element.
  keyParameter?: string;
}

// One kind of workflow node.
export interface FlowNodeType {
  name: string;
  label: string;
  description?: string;
  category: string[];
  // Highest priority among the matching entries is selected.
  priority: number;
  // The BPMN element the entry is drawn as, such as `"serviceTask"`.
  xmlElement: string;
  // A BPMN child element the drawn element must also have, such as `"timerEventDefinition"`.
  xmlChildElement?: string;
  // The IAP element under `bpmn:extensionElements` that names the entry, such as `"notify"`.
  xmlExtensionElement?: string;
  jcrNodeType?: string;
  // Whether the Element Type field switches to this entry, as it does to Exclusive Gateway for Gateway.
  elementTypeDefault: boolean;
  parameters: Parameter[];
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const optionalText = (value: unknown): string | undefined =>
  typeof value === "string" && value !== "" ? value : undefined;

const count = (value: unknown, fallback: number): number =>
  typeof value === "number" && Number.isFinite(value) ? value : fallback;

const strings = (value: unknown): string[] =>
  Array.isArray(value) ? value.map(text).filter(Boolean) : [];

export const isRequired = (parameter: Parameter): boolean => parameter.minValues >= 1;

// A bound of anything but exactly one takes a list, as on the server.
export const isMultiple = (parameter: Parameter): boolean => parameter.maxValues !== 1;

const STRUCTURE = "structure";

export const isStructure = (parameter: Parameter): boolean => parameter.dataType === STRUCTURE;

export const fieldsOf = (parameter: Parameter): Parameter[] => parameter.parameters ?? [];

// An element name reduced to its local part, as the server keeps it: `iap:notify` and `notify` are the same.
const bareName = (value: unknown): string | undefined => {
  const named = optionalText(value);
  return named === undefined ? undefined : optionalText(localName(named));
};

function parseOption(raw: unknown): ParameterOption | undefined {
  if (!isRecord(raw)) {
    return undefined;
  }
  const value = text(raw.value);
  return value === "" ? undefined : { value, label: text(raw.label) || value };
}

function parseParameter(raw: unknown): Parameter | undefined {
  if (!isRecord(raw)) {
    return undefined;
  }
  const name = text(raw.name);
  if (name === "") {
    return undefined;
  }
  return {
    name,
    label: text(raw.label) || name,
    description: optionalText(raw.description),
    dataType: text(raw.dataType) || "text",
    minValues: count(raw.minValues, 0),
    maxValues: count(raw.maxValues, 1),
    xmlLocation: XML_LOCATIONS.find(location => location === raw.xmlLocation) ?? "extensionElement",
    xmlPath: text(raw.xmlPath).split("/").map(step => localName(step.trim())).filter(Boolean),
    referenceType: optionalText(raw.referenceType),
    options: Array.isArray(raw.options) ? raw.options.flatMap(option => parseOption(option) ?? []) : [],
    parameters: Array.isArray(raw.parameters) ? raw.parameters.flatMap(field => parseParameter(field) ?? []) : [],
    keyParameter: optionalText(raw.keyParameter),
  };
}

function parseEntry(raw: unknown): FlowNodeType | undefined {
  if (!isRecord(raw)) {
    return undefined;
  }
  const name = text(raw.name);
  const xmlElement = text(raw.xmlElement);
  // An entry without a name or an element can be neither offered nor matched, and is skipped.
  if (name === "" || xmlElement === "") {
    return undefined;
  }
  return {
    name,
    label: text(raw.label) || name,
    description: optionalText(raw.description),
    category: strings(raw.category),
    priority: count(raw.priority, 0),
    xmlElement,
    xmlChildElement: optionalText(raw.xmlChildElement),
    xmlExtensionElement: bareName(raw.xmlExtensionElement),
    jcrNodeType: optionalText(raw.jcrNodeType),
    elementTypeDefault: raw.elementTypeDefault === true,
    parameters: Array.isArray(raw.parameters)
      ? raw.parameters.flatMap(parameter => parseParameter(parameter) ?? [])
      : [],
  };
}

// The documented vocabulary as one flat list. The server repeats an entry under each of its categories,
// so the groups are flattened and the repeats collapsed by name.
export function parseWorkflowTypes(raw: unknown): FlowNodeType[] {
  const groups = isRecord(raw) && isRecord(raw.items) ? Object.values(raw.items) : [];
  const byName = new Map<string, FlowNodeType>();
  for (const group of groups) {
    for (const raw of Array.isArray(group) ? group : []) {
      const entry = parseEntry(raw);
      if (entry !== undefined && !byName.has(entry.name)) {
        byName.set(entry.name, entry);
      }
    }
  }
  return [ ...byName.values() ];
}

// Fetched each time an editor opens, since the vocabulary can be edited on a running server. Canvases opening
// at once share one request. A failed request is not remembered.
let request: Promise<FlowNodeType[]> | null = null;

export function loadWorkflowTypes(fetchUtil: AuthenticatedFetch): Promise<FlowNodeType[]> {
  request ??= readNode(fetchUtil, WORKFLOW_TYPES_PATH, "doc")
    .then(parseWorkflowTypes)
    .finally(() => request = null);
  return request;
}

// The names of the BPMN child elements an element is written with, which the server's parser checks an
// entry's required child against.
const childElementNames = (businessObject: ModdleElement): string[] =>
  bpmnChildren(businessObject).map(child => child.name);

// Entry names ranked by code unit rather than by locale. Names are unique.
const byName = (one: string, other: string): number => one < other ? -1 : 1;

// Every entry drawn as this element, most specific first. Name breaks a tie in priority, as on the server.
function candidateTypes(businessObject: ModdleElement, types: readonly FlowNodeType[]): FlowNodeType[] {
  const drawn = elementName(businessObject);
  return drawn === "" ? [] : types.filter(type => localName(type.xmlElement) === drawn)
    .sort((one, other) => other.priority - one.priority || byName(one.name, other.name));
}

// The candidates this element could be made to mean: those whose required BPMN child it carries. The
// extension element is not required, since a change of kind adds it. An entry naming no node type is
// passed over, as the server parser does.
export function applicableTypes(
  businessObject: ModdleElement,
  types: readonly FlowNodeType[]
): FlowNodeType[] {
  const children = new Set(childElementNames(businessObject));
  return candidateTypes(businessObject, types)
    .filter(type => (type.jcrNodeType ?? "").trim() !== "")
    .filter(type => type.xmlChildElement === undefined || children.has(localName(type.xmlChildElement)));
}

// What this element currently means: the most specific applicable entry whose extension element is
// either not asked for or present.
export function matchFlowNodeType(
  businessObject: ModdleElement,
  types: readonly FlowNodeType[]
): FlowNodeType | undefined {
  const extensions = iapExtensionNames(businessObject);
  return applicableTypes(businessObject, types)
    .find(type => type.xmlExtensionElement === undefined || extensions.has(type.xmlExtensionElement));
}

// The heading an entry is listed under as the kind of BPMN element it is drawn as, such as "Service Task"
// or "Start Event": its first category. An entry without one is listed under its element's name.
export const elementTypeOf = (type: FlowNodeType): string => type.category.at(0) ?? localName(type.xmlElement);

// The finer heading within its element type, such as "Content" for Create Content. Its second category.
export const typeGroupOf = (type: FlowNodeType): string => type.category.at(1) ?? elementTypeOf(type);

// An element is never changed to these, or from them: a boundary event is attached to an activity, and a
// sequence flow joins two elements.
const FIXED_ELEMENTS = [ "boundaryEvent", "sequenceFlow" ];

const fixed = (businessObject: ModdleElement): boolean => FIXED_ELEMENTS.includes(elementName(businessObject));

// The entries of an element type, in label order.
export const entriesOf = (types: readonly FlowNodeType[], elementType: string): FlowNodeType[] =>
  types.filter(type => elementTypeOf(type) === elementType)
    .sort((one, other) => one.label.localeCompare(other.label) || byName(one.name, other.name));

// What an element changed to this element type becomes: the type's flagged default, else its first entry that
// needs neither an extension element nor an event definition.
function defaultEntry(types: readonly FlowNodeType[], elementType: string): FlowNodeType | undefined {
  const entries = entriesOf(types, elementType);
  return entries.find(type => type.elementTypeDefault)
    ?? entries.find(type => type.xmlExtensionElement === undefined && type.xmlChildElement === undefined);
}

// The element types a drawn element can be changed to, as `[ name, entry it becomes ]` pairs. None for an
// element that cannot change, or that the vocabulary does not describe.
export function switchableTypes(
  businessObject: ModdleElement,
  types: readonly FlowNodeType[]
): Map<string, FlowNodeType> {
  const found = new Map<string, FlowNodeType>();
  if (fixed(businessObject) || matchFlowNodeType(businessObject, types) === undefined) {
    return found;
  }
  [ ...new Set(types.map(elementTypeOf)) ].sort().forEach(elementType => {
    const entry = defaultEntry(types, elementType);
    if (entry !== undefined && !FIXED_ELEMENTS.includes(localName(entry.xmlElement))) {
      found.set(elementType, entry);
    }
  });
  return found;
}

// What bpmn-js is asked to make: the type of element, and the event it carries if it is an event.
export interface ReplaceTarget {
  type: string;
  eventDefinitionType?: string;
}

const isEventDefinition = (name: string): boolean => name.endsWith("EventDefinition");

// What the element has to be redrawn as to mean the entry, or nothing if it already is drawn as it. Choosing
// an entry that adds only an extension element leaves the shape as it is.
export function replacementFor(businessObject: ModdleElement, entry: FlowNodeType): ReplaceTarget | undefined {
  const wanted = localName(entry.xmlChildElement ?? "");
  const children = childElementNames(businessObject);
  const sameEvent = entry.xmlChildElement === undefined
    ? !children.some(isEventDefinition) : children.includes(wanted);
  if (elementName(businessObject) === localName(entry.xmlElement) && sameEvent) {
    return undefined;
  }
  return {
    type: `bpmn:${capitalised(localName(entry.xmlElement))}`,
    eventDefinitionType: entry.xmlChildElement !== undefined && isEventDefinition(wanted)
      ? `bpmn:${capitalised(wanted)}` : undefined,
  };
}
