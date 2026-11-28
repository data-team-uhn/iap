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


import type { Choice } from "@iap/frontend-commons/inputs/choices";
import { isNode, stringsOf } from "@iap/frontend-commons/serializedNode";
import { capitalize } from "@iap/frontend-commons/words";

import { bpmnChildren, elementName, iapExtensionNames, localName, text } from "./moddle";

import type { ModdleElement } from "./moddle";
import type { ReplaceTarget } from "./panelEdits";

export const WORKFLOW_TYPES_PATH = "/WorkflowTypes";

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
  // The node type a reference points at, such as `"wf:WorkflowVersion"`.
  referenceType?: string;
  // The values offered for it, such as `{ value: "draft", label: "Draft" }`
  options: Choice[];
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
  // The heading the entry is listed under within its element type, such as "Content" for Create Content.
  group?: string;
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

const optionalText = (value: unknown): string | undefined =>
  typeof value === "string" && value !== "" ? value : undefined;

const count = (value: unknown, fallback: number): number =>
  typeof value === "number" && Number.isFinite(value) ? value : fallback;

const strings = (value: unknown): string[] =>
  Array.isArray(value) ? value.map(text).filter(Boolean) : [];

export const isRequired = (parameter: Parameter): boolean => parameter.minValues >= 1;

// A bound of anything but exactly one takes a list, as on the server.
export const isMultiple = (parameter: Parameter): boolean => parameter.maxValues !== 1;

const STRUCTURE_DATA_TYPE = "structure";

export const isStructure = (parameter: Parameter): boolean => parameter.dataType === STRUCTURE_DATA_TYPE;

export const fieldsOf = (parameter: Parameter): Parameter[] => parameter.parameters ?? [];

// Whether a structure names one of its single-valued fields as its key. The server stores none of a repeated
// structure without one.
export const hasSingleValuedKey = (parameter: Parameter): boolean => fieldsOf(parameter)
  .some(field => field.name === parameter.keyParameter && !isStructure(field) && !isMultiple(field));

// An element name reduced to its local part, as the server keeps it: `iap:notify` and `notify` are the same.
const bareName = (value: unknown): string | undefined => {
  const named = optionalText(value);
  return named === undefined ? undefined : optionalText(localName(named));
};

function parseOption(raw: unknown): Choice | undefined {
  if (!isNode(raw)) {
    return undefined;
  }
  const value = text(raw.value);
  return value === "" ? undefined : { value, label: text(raw.label) || value };
}

function parseParameter(raw: unknown): Parameter | undefined {
  if (!isNode(raw)) {
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
  if (!isNode(raw)) {
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
    group: optionalText(raw.group),
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

// The documented vocabulary as one flat list. The server lists each entry once, under its element type.
export function parseWorkflowTypes(raw: unknown): FlowNodeType[] {
  const groups = isNode(raw) && isNode(raw.items) ? Object.values(raw.items) : [];
  return groups.flatMap(group => Array.isArray(group) ? group as unknown[] : [])
    .flatMap(entry => parseEntry(entry) ?? []);
}

// What matching reads of an element: the name it is written as, the names of its BPMN children and the names of
// the IAP elements under its `bpmn:extensionElements`.
interface DrawnElement {
  name: string;
  children: ReadonlySet<string>;
  extensions: ReadonlySet<string>;
}

// The BPMN children are named as the server's parser sees them, which is what an entry's required child is
// checked against.
const drawnAs = (businessObject: ModdleElement): DrawnElement => ({
  name: elementName(businessObject),
  children: new Set(bpmnChildren(businessObject).map(child => child.name)),
  extensions: iapExtensionNames(businessObject),
});

// Entry names ranked by code unit rather than by locale. Names are unique.
const byName = (one: string, other: string): number => one < other ? -1 : 1;

const hasNodeType = (type: FlowNodeType): boolean => (type.jcrNodeType ?? "").trim() !== "";

// The entries drawn as this element whose required BPMN child it carries, most specific first. Name breaks a tie
// in priority, as on the server. An entry naming no node type is passed over, as the server's parser does.
function entriesDrawnAs(drawn: DrawnElement, types: readonly FlowNodeType[]): FlowNodeType[] {
  return drawn.name === "" ? [] : types.filter(type => localName(type.xmlElement) === drawn.name)
    .filter(hasNodeType)
    .filter(type => type.xmlChildElement === undefined || drawn.children.has(localName(type.xmlChildElement)))
    .sort((one, other) => other.priority - one.priority || byName(one.name, other.name));
}

// The most specific of them whose extension element is either not asked for or present.
const matchDrawn = (drawn: DrawnElement, types: readonly FlowNodeType[]): FlowNodeType | undefined =>
  entriesDrawnAs(drawn, types)
    .find(type => type.xmlExtensionElement === undefined || drawn.extensions.has(type.xmlExtensionElement));

// The entries a drawn element matches, before its extension elements are looked at, most specific first.
export const applicableTypes = (businessObject: ModdleElement, types: readonly FlowNodeType[]): FlowNodeType[] =>
  entriesDrawnAs(drawnAs(businessObject), types);

// What this element currently means: the most specific applicable entry whose extension element is
// either not asked for or present.
export const matchFlowNodeType = (
  businessObject: ModdleElement,
  types: readonly FlowNodeType[]
): FlowNodeType | undefined => matchDrawn(drawnAs(businessObject), types);

// Whether changing an element to this entry leaves it meaning this entry. The change draws the entry's element
// with its required child and its extension element. An entry outranked by another one matching that element
// can never be reached.
export function isReachable(entry: FlowNodeType, types: readonly FlowNodeType[]): boolean {
  const changed: DrawnElement = {
    name: localName(entry.xmlElement),
    children: new Set(stringsOf(entry.xmlChildElement).map(localName)),
    extensions: new Set(stringsOf(entry.xmlExtensionElement)),
  };
  return matchDrawn(changed, types)?.name === entry.name;
}

// The BPMN elements the server's parser passes over on purpose: notes, groups, data, lanes and pools. They
// mean nothing to a workflow.
const SKIPPED_ELEMENTS = [
  "textAnnotation",
  "association",
  "group",
  "dataObject",
  "dataObjectReference",
  "dataStoreReference",
  "laneSet",
  "lane",
  "participant",
];

export const isSkippedElement = (name: string): boolean => SKIPPED_ELEMENTS.includes(name);

// The heading an entry is listed under as the kind of BPMN element it is drawn as, such as "Service Task"
// or "Start Event": its first category. An entry without one is listed under its element's name.
export const elementTypeOf = (type: FlowNodeType): string => type.category.at(0) ?? localName(type.xmlElement);

// The finer heading within its element type, such as "Content" for Create Content. An entry without a group is
// listed under its element type.
export const typeGroupOf = (type: FlowNodeType): string => type.group ?? elementTypeOf(type);

// A boundary event is attached to an activity, and a sequence flow joins two elements. Neither is ever
// redrawn as another element, nor another element as one of them.
const FIXED_ELEMENTS = [ "boundaryEvent", "sequenceFlow" ];

const ACTIVITY_ELEMENTS = [ "task", "callActivity", "subProcess" ];

const INTERMEDIATE_EVENT_ELEMENTS = [ "intermediateCatchEvent", "intermediateThrowEvent" ];

// The family of BPMN elements an entry's element can be redrawn within and keep its connections: activities,
// start events, intermediate events, end events or gateways. None for a fixed element.
function familyOf(type: FlowNodeType): string | undefined {
  const element = localName(type.xmlElement);
  if (FIXED_ELEMENTS.includes(element)) {
    return undefined;
  }
  if (element.endsWith("Task") || ACTIVITY_ELEMENTS.includes(element)) {
    return "activity";
  }
  if (INTERMEDIATE_EVENT_ELEMENTS.includes(element)) {
    return "intermediateEvent";
  }
  return element.endsWith("Gateway") ? "gateway" : element;
}

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

// The element types an element meaning this entry can be changed to, by name, each with the entry it becomes.
// Only those in the entry's family are, its own included. None for a fixed element.
export function switchableTypes(matched: FlowNodeType, types: readonly FlowNodeType[]): Map<string, FlowNodeType> {
  const family = familyOf(matched);
  return new Map([ ...new Set(types.map(elementTypeOf)) ].sort()
    .flatMap(elementType => defaultEntry(types, elementType) ?? [])
    .filter(entry => family !== undefined && familyOf(entry) === family)
    .map(entry => [ elementTypeOf(entry), entry ]));
}

const isEventDefinition = (name: string): boolean => name.endsWith("EventDefinition");

// The BPMN attributes bpmn-js sets on a redrawn element only from what it is asked to make. Left out, a
// non-interrupting event would be redrawn interrupting.
const INTERRUPTION_ATTRIBUTES = [ "cancelActivity", "isInterrupting" ];

const interruptionOf = (businessObject: ModdleElement): Record<string, boolean> => Object.fromEntries(
  INTERRUPTION_ATTRIBUTES.filter(name => Object.hasOwn(businessObject, name) && typeof businessObject[name] === "boolean")
    .map(name => [ name, businessObject[name] as boolean ])
);

// What the element has to be redrawn as to mean the entry, or nothing if it already is drawn as it. Choosing
// an entry that adds only an extension element leaves the shape as it is.
export function replacementFor(businessObject: ModdleElement, entry: FlowNodeType): ReplaceTarget | undefined {
  const wanted = localName(entry.xmlChildElement ?? "");
  const drawn = drawnAs(businessObject);
  const sameEvent = entry.xmlChildElement === undefined
    ? ![ ...drawn.children ].some(isEventDefinition) : drawn.children.has(wanted);
  if (drawn.name === localName(entry.xmlElement) && sameEvent) {
    return undefined;
  }
  return {
    type: `bpmn:${capitalize(localName(entry.xmlElement))}`,
    eventDefinitionType: entry.xmlChildElement !== undefined && isEventDefinition(wanted)
      ? `bpmn:${capitalize(wanted)}` : undefined,
    ...interruptionOf(businessObject),
  };
}
