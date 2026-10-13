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

// The vocabulary of workflow node kinds, as the server documents it, and the matching rule that says
// which entry a drawn BPMN element means.
//
// This is content rather than code on the server. The editor knows nothing about kinds of node beyond
// what an entry says: which XML element it stands for, and which parameters it accepts. A deployment
// that adds a service task handler adds an entry with it, and the properties panel offers its
// parameters without a frontend change.
//
// The matching rule below is the editor's copy of the server parser's. The same element has to resolve
// to the same entry on both sides, or a diagram would be stored as something other than what was drawn.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { readNode } from "@iap/frontend-commons/useNode";

import { bpmnChildren, elementName, iapExtensionNames, localName, text } from "./moddle";

import type { ModdleElement } from "./moddle";

// The vocabulary documents itself, so there is nothing to configure here and nothing to keep in step
// with the entries a deployment happens to ship.
export const WORKFLOW_TYPES_PATH = "/WorkflowTypes";

// One choice offered for a parameter that constrains its values.
export interface ParameterOption {
  value: string;
  label: string;
}

// Where a diagram writes a parameter's value. IAP's markup is the entry's extension element or an
// `iap:` attribute of the flow node itself. BPMN's own is one of its attributes or an element's text.
export type XmlLocation = "extensionElement" | "attribute" | "bpmnAttribute" | "bpmnElement";

const XML_LOCATIONS: readonly XmlLocation[] = [ "extensionElement", "attribute", "bpmnAttribute", "bpmnElement" ];

// One value a flow node of some kind carries: where it is stored, how it is typed, and how many
// values it takes.
export interface Parameter {
  name: string;
  label: string;
  description?: string;
  // What may be put in it: `text`, `boolean`, `long`, `reference`, `principal`, or `structure` for a
  // parameter holding fields of its own. It picks the field the value is edited in.
  dataType: string;
  // How many values are required; anything above zero makes the parameter mandatory.
  minValues: number;
  // How many values are accepted, zero meaning any number.
  maxValues: number;
  // See the location rule in bpmnParameters.
  xmlLocation: XmlLocation;
  // For a value BPMN holds, the local names of the BPMN child elements leading from the flow node to
  // the one holding it. Empty when the flow node holds it itself.
  xmlPath: string[];
  // The node type a reference-typed value has to point at, when the entry names one.
  referenceType?: string;
  options: ParameterOption[];
  // For a structure, its fields. Absent or empty for any other type.
  parameters?: Parameter[];
  // For a repeated structure, the field whose value names each element.
  keyParameter?: string;
}

// One kind of workflow node: the BPMN element it is drawn as, what else an element needs to mean this
// kind, and the parameters it accepts.
export interface FlowNodeType {
  name: string;
  label: string;
  description?: string;
  category: string[];
  // How specific this entry is: the highest-priority matching entry wins, which is what stops every
  // service task from reading as the generic one.
  priority: number;
  xmlElement: string;
  // A BPMN child element that has to be present too, which is how the flavours of an event differ.
  xmlChildElement?: string;
  // The IAP-namespaced element under `bpmn:extensionElements` that names this entry and holds its
  // element-located parameters.
  xmlExtensionElement?: string;
  jcrNodeType?: string;
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

// Whether a parameter has to be given a value at all, and whether it takes more than one.
export const isRequired = (parameter: Parameter): boolean => parameter.minValues >= 1;

// Anything but a bound of exactly one takes a list, unbounded (zero) included. The server reads it the
// same way.
export const isMultiple = (parameter: Parameter): boolean => parameter.maxValues !== 1;

// The data type reserved for a parameter whose values are elements holding fields of their own.
const STRUCTURE = "structure";

export const isStructure = (parameter: Parameter): boolean => parameter.dataType === STRUCTURE;

// The fields of a structure, in the order they are declared.
export const fieldsOf = (parameter: Parameter): Parameter[] => parameter.parameters ?? [];

// An element name as the rest of this module compares it. The server keeps only the local part, so an
// entry naming `iap:notify` and one naming `notify` mean the same element.
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
  // A parameter with no name names no attribute or element, so there is nowhere to store its value.
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
  // An entry that does not say what it is called and what it is drawn as can be neither offered nor
  // matched. It is skipped rather than taking the rest of the vocabulary with it.
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
    parameters: Array.isArray(raw.parameters)
      ? raw.parameters.flatMap(parameter => parseParameter(parameter) ?? [])
      : [],
  };
}

// The documented vocabulary as one flat list. The server groups entries by category, repeating an entry
// under each of its categories. Matching ignores categories, so the groups are flattened and the
// repeats collapsed by name.
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

// Cached for the life of the session. Every element selected in every diagram is matched against this
// vocabulary, and it only changes when a bundle is installed or removed. An in-flight request is shared,
// so several canvases opening at once ask the server only once. A failure is not cached: the next canvas
// asks again, since a refused or unreachable request says nothing about the vocabulary itself.
let documented: FlowNodeType[] | null = null;
let request: Promise<FlowNodeType[]> | null = null;

export function loadWorkflowTypes(fetchUtil: AuthenticatedFetch): Promise<FlowNodeType[]> {
  if (documented) {
    return Promise.resolve(documented);
  }
  request ??= readNode(fetchUtil, WORKFLOW_TYPES_PATH, "doc")
    .then(raw => documented = parseWorkflowTypes(raw))
    .finally(() => request = null);
  return request;
}

// Forgets the vocabulary, so that the next ask goes to the server. For tests, and for a caller that
// has reason to believe the vocabulary has changed under it.
export function forgetWorkflowTypes(): void {
  documented = null;
  request = null;
}

// The names of the BPMN child elements an element is written with, which is what the server's parser
// looks among for an entry's required child. An object with no descriptor of its own is a stand-in,
// and its event definitions are all it can carry.
function childElementNames(businessObject: ModdleElement): string[] {
  return businessObject.$descriptor?.properties === undefined
    ? (businessObject.eventDefinitions ?? []).map(elementName)
    : bpmnChildren(businessObject).map(child => child.name);
}

// Entry names ranked by code unit rather than by locale, which is the order the server ranks them in.
// Never asked about two equal names: the vocabulary is deduped by name before anything is sorted.
const byName = (one: string, other: string): number => one < other ? -1 : 1;

// Every entry drawn as this element, most specific first. A change of kind picks from these, before
// anything about the element's own content is taken into account. Name breaks a tie, so two entries of
// equal priority are ranked the same way here as on the server.
function candidateTypes(businessObject: ModdleElement, types: readonly FlowNodeType[]): FlowNodeType[] {
  const drawn = elementName(businessObject);
  return drawn === "" ? [] : types.filter(type => localName(type.xmlElement) === drawn)
    .sort((one, other) => other.priority - one.priority || byName(one.name, other.name));
}

// The candidates this element could be made to mean: those whose required BPMN child it carries. This
// is the list a change of kind chooses from, so the extension element, which the change adds, is not
// required. An entry naming no node type is passed over, as the server parser does.
function applicableTypes(
  businessObject: ModdleElement,
  types: readonly FlowNodeType[]
): FlowNodeType[] {
  const children = new Set(childElementNames(businessObject));
  return candidateTypes(businessObject, types)
    .filter(type => (type.jcrNodeType ?? "").trim() !== "")
    .filter(type => type.xmlChildElement === undefined || children.has(localName(type.xmlChildElement)));
}

// The applicable entries a change of kind can actually reach. Choosing an entry only brings its
// extension element in. An entry ranked below one that asks for none would still read as the higher
// one afterwards, so offering it would offer a choice that changes nothing.
export function selectableTypes(
  businessObject: ModdleElement,
  types: readonly FlowNodeType[]
): FlowNodeType[] {
  const applicable = applicableTypes(businessObject, types);
  const unconditional = applicable.findIndex(type => type.xmlExtensionElement === undefined);
  return unconditional < 0 ? applicable : applicable.slice(0, unconditional + 1);
}

// What this element currently means: the most specific applicable entry whose extension element is
// either not asked for or present. Matching the server parser exactly matters more than being
// generous, since this is what says how the drawn diagram will be stored.
export function matchFlowNodeType(
  businessObject: ModdleElement,
  types: readonly FlowNodeType[]
): FlowNodeType | undefined {
  const extensions = iapExtensionNames(businessObject);
  return applicableTypes(businessObject, types)
    .find(type => type.xmlExtensionElement === undefined || extensions.has(type.xmlExtensionElement));
}
