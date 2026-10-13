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

// What both the vocabulary matching and the parameter rules read off a moddle object: its element
// name, its BPMN children and its IAP elements.

// The namespace every IAP-specific attribute and element in a diagram belongs to. Matching is always
// by this URI. A document may bind it to any prefix, such as the `ns0:` a tool that did not author it
// tends to use. Rewriting has to keep the prefix the document already chose.
export const IAP_NS = "https://iap.uhndata.io/bpmn";

// The parts of a moddle object this module reads. bpmn-js types them as `any`, which would take every
// property access here out of the compiler's reach.
export interface ModdleElement {
  id?: string;
  $type?: string;
  $body?: string;
  $attrs?: Record<string, string | undefined>;
  $descriptor?: {
    ns?: { prefix?: string; localName?: string; uri?: string };
    properties?: ModdleProperty[];
    // The package declaring the type. Absent for an element built with `createAny`.
    $pkg?: { xml?: { tagAlias?: string } };
  };
  $parent?: ModdleElement;
  $children?: ModdleElement[];
  extensionElements?: ModdleElement;
  values?: ModdleElement[];
  eventDefinitions?: ModdleElement[];
  set?: (name: string, value: unknown) => void;
  [key: string]: unknown;
}

// What moddle's descriptor says about one property of a BPMN element: which XML attribute or child
// element it is written as, and what it holds.
export interface ModdleProperty {
  name: string;
  type?: string;
  isAttr?: boolean;
  isMany?: boolean;
  isReference?: boolean;
  default?: unknown;
  xml?: { serialize?: string };
}

// The moddle instance the canvas exposes as its `moddle` service, narrowed to what building an edit
// needs: `createAny` for elements no package declares, `create` for the BPMN ones.
export interface Moddle {
  create: (type: string, attributes?: Record<string, unknown>) => ModdleElement;
  createAny: (type: string, ns: string, attributes?: Record<string, unknown>) => ModdleElement;
}

export const text = (value: unknown): string => typeof value === "string" ? value : "";

// The local part of a namespaced XML element name, e.g. `serviceTask` of `bpmn:serviceTask`. What a
// document prefixes an element with is its own business, so only the local part is ever compared.
export const localName = (qualified: string): string => qualified.slice(qualified.indexOf(":") + 1);

const lowerInitial = (name: string): string => name.charAt(0).toLowerCase() + name.slice(1);

// The element name a moddle object is written as, whatever prefix the document gave it, as the
// server's parser compares it. A BPMN type is named `ServiceTask` but written `serviceTask`, which its
// package declares with `tagAlias: "lowerCase"`. An element no package declares, an IAP one included,
// is written as named. An object built without a descriptor has only its `$type` to go on, and a
// `bpmn:` one is written the BPMN way.
export function elementName(element: ModdleElement): string {
  const descriptor = element.$descriptor;
  const named = descriptor?.ns?.localName;
  if (named === undefined) {
    const type = element.$type ?? "";
    return type.startsWith("bpmn:") ? lowerInitial(localName(type)) : localName(type);
  }
  return descriptor?.$pkg?.xml?.tagAlias === "lowerCase" ? lowerInitial(named) : named;
}

// Whether a moddle object is the IAP-namespaced element of the given local name. Never compares
// `$type`, which carries the prefix the document happened to use.
export function isIapElement(element: ModdleElement | undefined, name: string | undefined): boolean {
  const ns = element?.$descriptor?.ns;
  return name !== undefined && ns?.uri === IAP_NS && ns.localName === name;
}

// The elements under a flow node's `bpmn:extensionElements`, in document order.
export const extensionValues = (businessObject: ModdleElement): ModdleElement[] =>
  businessObject.extensionElements?.values ?? [];

// The IAP elements among them.
const iapExtensions = (businessObject: ModdleElement): ModdleElement[] =>
  extensionValues(businessObject).filter(value => value.$descriptor?.ns?.uri === IAP_NS);

// The local names of the IAP elements under the flow node's `bpmn:extensionElements`.
export const iapExtensionNames = (businessObject: ModdleElement): Set<string> =>
  new Set(iapExtensions(businessObject).flatMap(value => value.$descriptor?.ns?.localName ?? []));

// One BPMN child element of a moddle object: the property holding it, and what it holds.
export interface BpmnChild {
  name: string;
  property: ModdleProperty;
  value: unknown;
}

// The BPMN child elements of a moddle object, named the way the server's parser sees them in the XML.
// A contained object is written under its element name. A reference, a plain value and anything
// moddle writes as the property itself, an expression among them, take the property's name.
export function bpmnChildren(element: ModdleElement): BpmnChild[] {
  return (element.$descriptor?.properties ?? []).filter(property => !property.isAttr).flatMap(property => {
    const held: unknown = element[property.name];
    return (Array.isArray(held) ? held as unknown[] : [ held ]).filter(value => value !== undefined && value !== null)
      .map(value => ({
        name: property.isReference || property.xml?.serialize !== undefined || typeof value !== "object"
          ? property.name : elementName(value as ModdleElement),
        property,
        value,
      }));
  });
}
