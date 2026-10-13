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

// Where a flow node's typed parameters live in a BPMN document, and how to read and rewrite them.
// This is the editor's half of the location rule the server's parser implements. The two have to
// agree exactly: the parser finds no value written anywhere else.
//
// Everything here works on plain moddle objects, so the rules can be exercised against real BPMN XML
// without a canvas. No moddle extension is registered for the IAP namespace on purpose. Declaring one
// makes moddle-xml drop every unknown `iap:` element on import. The values ride along as generic
// attributes and `createAny` elements instead, matched by namespace URI rather than by prefix.

import { bpmnChildren, extensionValues, IAP_NS, isIapElement, localName, text } from "./moddle";
import {
  recordValues,
  sameRecord,
  sameValues,
  settled,
  splitList,
  textValues,
  valuesOf,
} from "./parameterValues";
import { fieldsOf, isMultiple, isStructure } from "./workflowTypes";

import type { Moddle, ModdleElement, ModdleProperty } from "./moddle";
import type { ParameterValue, StructureRecord } from "./parameterValues";
import type { FlowNodeType, Parameter } from "./workflowTypes";

// The prefix bound to the namespace when a document does not bind it yet.
export const DEFAULT_IAP_PREFIX = "iap";

const XMLNS = "xmlns:";

// One change to hand to bpmn-js's `modeling.updateModdleProperties`, which applies it to the named
// moddle object undoably and reports the flow node as changed.
export interface ParameterEdit {
  moddleElement: ModdleElement;
  properties: Record<string, unknown>;
}

// A value moddle holds as a string, a boolean or a number, as the text a document writes it as.
const scalar = (value: unknown): string | undefined =>
  typeof value === "string" || typeof value === "boolean" || typeof value === "number" ? String(value) : undefined;

// The prefixes one element binds the IAP namespace to itself.
const boundOn = (element: ModdleElement): string[] => Object.entries(element.$attrs ?? {})
  .filter(([ name, uri ]) => name.startsWith(XMLNS) && uri === IAP_NS)
  .map(([ name ]) => name.slice(XMLNS.length));

// Every prefix the IAP namespace is bound to where this element sits, nearest first. A document may
// bind it on any ancestor, the process as readily as the definitions. The definitions are asked last
// in their own right, since an element built by an edit has no parent to walk up from yet.
function iapPrefixes(element: ModdleElement, definitions: ModdleElement): string[] {
  const found: string[] = [];
  for (let at: ModdleElement | undefined = element; at !== undefined; at = at.$parent) {
    found.push(...boundOn(at));
  }
  return [ ...new Set([ ...found, ...boundOn(definitions) ]) ];
}

// The prefix every IAP attribute and element written onto this element goes under. It is the binding in
// force there, or a new one on the document when there is none. A prefixed attribute whose namespace
// the document does not declare is dropped on save without failing, so the binding comes first.
function writePrefix(element: ModdleElement, definitions: ModdleElement): string {
  const bound = iapPrefixes(element, definitions).at(0);
  if (bound === undefined) {
    definitions.set?.(`${XMLNS}${DEFAULT_IAP_PREFIX}`, IAP_NS);
  }
  return bound ?? DEFAULT_IAP_PREFIX;
}

// The key an IAP attribute is held under on this element, whichever bound prefix it was written with.
// On a canvas with no document open nothing binds the namespace, so it is looked for under the prefix
// a diagram of ours is saved with.
function attributeKey(element: ModdleElement, definitions: ModdleElement, localName: string): string | undefined {
  const bound = iapPrefixes(element, definitions);
  return (bound.length === 0 ? [ DEFAULT_IAP_PREFIX ] : bound).map(prefix => `${prefix}:${localName}`)
    .find(key => element.$attrs?.[key] !== undefined);
}

// The IAP element of the given local name under the flow node's `bpmn:extensionElements`, which is
// both where an entry's parameters live and how the entry itself is recognized.
function iapExtension(businessObject: ModdleElement, name: string | undefined) {
  return extensionValues(businessObject).find(value => isIapElement(value, name));
}

// An entry with no extension element has nowhere but the flow node's own attributes to put a value.
// A parameter that asks for one is read and written as an attribute, the fallback the server's parser
// applies after reporting the entry as malformed.
export const locatedInAttribute = (type: FlowNodeType, parameter: Parameter): boolean =>
  parameter.xmlLocation === "attribute"
    || parameter.xmlLocation === "extensionElement" && type.xmlExtensionElement === undefined;

// Whether a list is kept in one attribute, IAP's or BPMN's, and so joined with commas.
export const commaSeparated = (type: FlowNodeType, parameter: Parameter): boolean =>
  isMultiple(parameter) && (locatedInAttribute(type, parameter) || parameter.xmlLocation === "bpmnAttribute");

// The moddle object at the end of a parameter's path, or undefined when the flow node lacks a step.
// Each step takes the first child of its name, as the server's parser does.
function bpmnHolder(businessObject: ModdleElement, parameter: Parameter): ModdleElement | undefined {
  let holder = businessObject;
  for (const step of parameter.xmlPath) {
    const next = bpmnChildren(holder).find(child => child.name === step && typeof child.value === "object");
    if (next === undefined) {
      return undefined;
    }
    holder = next.value as ModdleElement;
  }
  return holder;
}

const descriptorProperty = (element: ModdleElement, name: string): ModdleProperty | undefined =>
  element.$descriptor?.properties?.find(property => property.name === name);

// An expression is written as an element whose text is its body. bpmn-moddle declares the property as
// a `bpmn:Expression` and creates a `bpmn:FormalExpression` for it.
const isExpression = (property: ModdleProperty | undefined): boolean =>
  [ "Expression", "FormalExpression" ].includes(localName(property?.type ?? ""));

// The raw text BPMN holds for a parameter: an attribute's value, or the text of every element of its
// name. An attribute left out reads as the default BPMN declares for it. Saving never writes a value
// equal to that default, so the document cannot say it any other way. The parser then stores nothing,
// leaving the node type's default, which a vocabulary names after BPMN's.
function readBpmn(businessObject: ModdleElement, parameter: Parameter): string[] {
  const holder = bpmnHolder(businessObject, parameter);
  if (holder === undefined) {
    return [];
  }
  if (parameter.xmlLocation === "bpmnAttribute") {
    const own = Object.prototype.hasOwnProperty.call(holder, parameter.name) ? holder[parameter.name] : undefined;
    const raw = scalar(own) ?? holder.$attrs?.[parameter.name]
      ?? scalar(descriptorProperty(holder, parameter.name)?.default);
    return raw === undefined ? [] : [ raw ];
  }
  return bpmnChildren(holder).filter(child => child.name === parameter.name)
    .map(child => scalar(child.value) ?? text((child.value as ModdleElement).body));
}

// What to set on the moddle object holding a BPMN-located parameter, in the type its property declares.
function bpmnValue(moddle: Moddle, property: ModdleProperty | undefined, parameter: Parameter, values: string[]) {
  if (values.length === 0) {
    return undefined;
  }
  if (parameter.xmlLocation === "bpmnAttribute") {
    const joined = values.join(", ");
    if (property?.type === "Boolean") {
      return joined.toLowerCase() === "true";
    }
    return property?.type === "Integer" || property?.type === "Real" ? Number(joined) : joined;
  }
  const written = values.map(body => isExpression(property) ? moddle.create("bpmn:FormalExpression", { body }) : body);
  return property?.isMany ? written : written[0];
}

// What a parameter currently holds, always as a list: one entry per value, empty when unset. Keeping
// one shape for every cardinality is what lets the fields and the edits below stay uniform.
function readParameter(
  businessObject: ModdleElement,
  definitions: ModdleElement,
  type: FlowNodeType,
  parameter: Parameter
): string[] {
  // Every value is trimmed, wherever it is written: the parser trims them all, and a value that differs
  // only by surrounding space is the same value.
  if (parameter.xmlLocation === "bpmnAttribute" || parameter.xmlLocation === "bpmnElement") {
    const values = readBpmn(businessObject, parameter);
    return commaSeparated(type, parameter) ? values.flatMap(splitList) : textValues(settled(values));
  }
  if (locatedInAttribute(type, parameter)) {
    const key = attributeKey(businessObject, definitions, parameter.name);
    const raw = text(key === undefined ? undefined : businessObject.$attrs?.[key]);
    return isMultiple(parameter) ? splitList(raw) : textValues(settled([ raw ]));
  }
  const extension = iapExtension(businessObject, type.xmlExtensionElement);
  return extension === undefined ? [] : textValues(readInside(extension, parameter));
}

// Whether a structure is declared somewhere the server does not read one from. Only an entry's own
// element can hold one.
const misplacedStructure = (type: FlowNodeType, parameter: Parameter): boolean =>
  isStructure(parameter) && (parameter.xmlLocation !== "extensionElement" || type.xmlExtensionElement === undefined);

// What a parameter currently holds, a structure's records included.
export function readValues(
  businessObject: ModdleElement,
  definitions: ModdleElement,
  type: FlowNodeType,
  parameter: Parameter
): ParameterValue[] {
  if (!isStructure(parameter)) {
    return readParameter(businessObject, definitions, type, parameter);
  }
  const extension = misplacedStructure(type, parameter) ? undefined
    : iapExtension(businessObject, type.xmlExtensionElement);
  return extension === undefined ? [] : readInside(extension, parameter);
}

// The IAP-namespaced children of an element named after a parameter: one per value of a list, or per
// record of a structure.
const namedChildren = (holder: ModdleElement, parameter: Parameter): ModdleElement[] =>
  (holder.$children ?? []).filter(child => isIapElement(child, parameter.name));

// The children of an element that hold no value of this parameter.
const otherChildren = (holder: ModdleElement, parameter: Parameter): ModdleElement[] =>
  (holder.$children ?? []).filter(child => !isIapElement(child, parameter.name));

const readRecord = (element: ModdleElement, parameter: Parameter): StructureRecord =>
  Object.fromEntries(fieldsOf(parameter).map(field => [ field.name, readInside(element, field) ]));

// The values a parameter has inside the element it is read from: the entry's own element, or one
// element of a structure. A single value is an unprefixed attribute of it, a list is one IAP-namespaced
// child per value. A structure is one child per record, holding its fields the same way.
function readInside(holder: ModdleElement, parameter: Parameter): ParameterValue[] {
  if (isStructure(parameter)) {
    return namedChildren(holder, parameter).map(element => readRecord(element, parameter));
  }
  return settled(isMultiple(parameter)
    ? namedChildren(holder, parameter).map(child => text(child.$body))
    : [ text(holder[parameter.name]) ]);
}

// What to write on the element a parameter is read from to give it these values. Children belonging to
// the element's other parameters are carried over, since one edit only ever speaks for one parameter.
function insideProperties(
  moddle: Moddle,
  prefix: string,
  parameter: Parameter,
  values: ParameterValue[],
  holder: ModdleElement
): Record<string, unknown> {
  if (!isMultiple(parameter) && !isStructure(parameter)) {
    return { [parameter.name]: textValues(values).at(0) };
  }
  const others = otherChildren(holder, parameter);
  const written = isStructure(parameter)
    ? recordValues(values).map(record => recordElement(moddle, prefix, parameter, record))
    : textValues(values).map(value => moddle.createAny(`${prefix}:${parameter.name}`, IAP_NS, { $body: value }));
  return { $children: [ ...others, ...written ] };
}

// A new element for one record of a structure, with every level below it.
function recordElement(moddle: Moddle, prefix: string, parameter: Parameter, record: StructureRecord): ModdleElement {
  const element = moddle.createAny(`${prefix}:${parameter.name}`, IAP_NS, {});
  for (const field of fieldsOf(parameter)) {
    Object.entries(insideProperties(moddle, prefix, field, valuesOf(record, field.name), element))
      // Nothing is written for a field left empty, not even an empty list of children
      .filter(([ , value ]) => value !== undefined && !(Array.isArray(value) && value.length === 0))
      .forEach(([ name, value ]) => {
        element[name] = value;
      });
  }
  return element;
}

// The edit that gives a structure the records wanted, or undefined when nothing changed. It is made on
// the deepest element the change is confined to, so everything else the diagram holds stays as it was.
// One record changed is edited in place. Records added, removed or moved rewrite the list, and a record
// that did not change keeps its element.
function structureEdit(
  moddle: Moddle,
  prefix: string,
  holder: ModdleElement,
  parameter: Parameter,
  wanted: StructureRecord[]
): ParameterEdit | undefined {
  const existing = namedChildren(holder, parameter);
  const held = existing.map(element => readRecord(element, parameter));
  if (held.length === wanted.length) {
    const changed = held.flatMap((record, index) => sameRecord(record, wanted[index]) ? [] : [ index ]);
    if (changed.length === 0) {
      return undefined;
    }
    if (changed.length === 1) {
      const [ index ] = changed;
      return recordEdit(moddle, prefix, existing[index], parameter, held[index], wanted[index]);
    }
  }
  const unused = existing.map((element, index) => ({ element, record: held[index] }));
  const elements = wanted.map(record => {
    const index = unused.findIndex(each => sameRecord(each.record, record));
    return index < 0 ? recordElement(moddle, prefix, parameter, record) : unused.splice(index, 1)[0].element;
  });
  return { moddleElement: holder, properties: { $children: [ ...otherChildren(holder, parameter), ...elements ] } };
}

// The edit that changes the fields of one record that differ, made on the record's own element. A change
// inside one of its structures alone is made further down.
function recordEdit(
  moddle: Moddle,
  prefix: string,
  element: ModdleElement,
  parameter: Parameter,
  held: StructureRecord,
  wanted: StructureRecord
): ParameterEdit | undefined {
  const changed = fieldsOf(parameter)
    .filter(field => !sameValues(valuesOf(held, field.name), valuesOf(wanted, field.name)));
  if (changed.length === 1 && isStructure(changed[0])) {
    return structureEdit(moddle, prefix, element, changed[0], recordValues(valuesOf(wanted, changed[0].name)));
  }
  const properties: Record<string, unknown> = {};
  for (const field of changed) {
    // A second list written in the same edit has to keep the children the first one wrote
    const current = { $children: properties.$children ?? element.$children } as ModdleElement;
    Object.assign(properties, insideProperties(moddle, prefix, field, valuesOf(wanted, field.name), current));
  }
  return { moddleElement: element, properties };
}

// The edit that gives a parameter the values passed in, or clears it when they are empty. Clearing
// removes an attribute rather than leaving it blank, and a list's children rather than leaving an empty
// element. The diagram then never carries a value the parser would reject. Undefined when the flow node
// lacks the element a value would be written in, BPMN's or the entry's own.
export function parameterEdit(
  moddle: Moddle,
  businessObject: ModdleElement,
  definitions: ModdleElement,
  type: FlowNodeType,
  parameter: Parameter,
  values: ParameterValue[]
): ParameterEdit | undefined {
  if (misplacedStructure(type, parameter)) {
    return undefined;
  }
  // The parser trims every value and drops a blank one
  const wanted = settled(values);
  if (parameter.xmlLocation === "bpmnAttribute" || parameter.xmlLocation === "bpmnElement") {
    const holder = bpmnHolder(businessObject, parameter);
    if (holder === undefined) {
      return undefined;
    }
    const property = descriptorProperty(holder, parameter.name);
    return {
      moddleElement: holder,
      properties: {
        [parameter.name]: bpmnValue(moddle, property, parameter, textValues(wanted)),
      },
    };
  }
  if (locatedInAttribute(type, parameter)) {
    const prefix = writePrefix(businessObject, definitions);
    const joined = textValues(wanted).join(", ");
    const key = attributeKey(businessObject, definitions, parameter.name) ?? `${prefix}:${parameter.name}`;
    return { moddleElement: businessObject, properties: { [key]: joined === "" ? undefined : joined } };
  }
  // An entry with an element of its own only matches a node carrying it. Without it, the edit was made
  // for what the node meant before an undo, and bringing the element back would reverse that undo.
  const extension = iapExtension(businessObject, type.xmlExtensionElement);
  if (extension === undefined) {
    return undefined;
  }
  const prefix = writePrefix(businessObject, definitions);
  return isStructure(parameter)
    ? structureEdit(moddle, prefix, extension, parameter, recordValues(wanted))
    : { moddleElement: extension, properties: insideProperties(moddle, prefix, parameter, wanted, extension) };
}

// The attribute-located parameters of an entry, by name.
const attributeNames = (type: FlowNodeType | undefined): Set<string> => new Set(type === undefined ? []
  : type.parameters.filter(parameter => locatedInAttribute(type, parameter)).map(parameter => parameter.name));

// The edit that makes a flow node mean a different vocabulary entry. The element naming the entry being
// left goes. The one naming the entry taken over arrives empty, unless the node already carries it and
// keeps what it holds. Anything else under `extensionElements`, such as another namespace's element or
// a condition subtree, belongs to another feature and stays. An attribute-located value stays when the
// entry taken over reads it too; one only the entry being left read goes, since nothing reads it after.
// BPMN's own markup always stays: it belongs to the element whatever entry reads it.
export function typeEdit(
  moddle: Moddle,
  businessObject: ModdleElement,
  definitions: ModdleElement,
  from: FlowNodeType | undefined,
  to: FlowNodeType
): ParameterEdit {
  const leaving = from?.xmlExtensionElement === to.xmlExtensionElement ? undefined : from?.xmlExtensionElement;
  const kept = extensionValues(businessObject).filter(value => !isIapElement(value, leaving));
  const values = to.xmlExtensionElement === undefined || kept.some(value => isIapElement(value, to.xmlExtensionElement))
    ? kept
    : [ ...kept, moddle.createAny(`${writePrefix(businessObject, definitions)}:${to.xmlExtensionElement}`, IAP_NS, {}) ];
  const staying = attributeNames(to);
  const dropped = [ ...attributeNames(from) ].filter(name => !staying.has(name))
    .flatMap(name => attributeKey(businessObject, definitions, name) ?? []);
  return {
    moddleElement: businessObject,
    properties: {
      // An empty container would serialize as an empty `bpmn:extensionElements` element
      extensionElements: values.length === 0 ? undefined : moddle.create("bpmn:ExtensionElements", { values }),
      ...Object.fromEntries(dropped.map(key => [ key, undefined ])),
    },
  };
}
