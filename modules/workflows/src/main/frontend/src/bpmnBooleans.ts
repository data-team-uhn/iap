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

// bpmn-moddle reads a boolean attribute as true only when it is written `true`. BPMN types its boolean
// attributes as XML Schema booleans, which also write true as `1` and false as `0`, and the server reads them
// that way. So such a value is spelled out before the editor imports the diagram, and the editor then reads
// it as the server does.

import { capitalize } from "@iap/frontend-commons/words";

import type { ModdleProperty } from "./moddle";

// What is read of the moddle instance the canvas imports with.
export interface ModdleTypes {
  getPackage: (uri: string) => { prefix: string } | undefined;
  getTypeDescriptor: (name: string) => unknown;
  getType: (name: string) => { $descriptor?: { properties?: ModdleProperty[] } };
}

const SPELLED_OUT: ReadonlyMap<string, string> = new Map([ [ "1", "true" ], [ "0", "false" ] ]);

// The names of the boolean attributes a moddle package declares for an element, by the element's XML name.
function booleanAttributes(moddle: ModdleTypes, element: Element): string[] {
  const prefix = element.namespaceURI === null ? undefined : moddle.getPackage(element.namespaceURI)?.prefix;
  const type = `${prefix}:${capitalize(element.localName)}`;
  if (prefix === undefined || moddle.getTypeDescriptor(type) === undefined) {
    return [];
  }
  return (moddle.getType(type).$descriptor?.properties ?? [])
    .filter(property => property.isAttr && property.type === "Boolean")
    .map(property => property.name.slice(property.name.indexOf(":") + 1));
}

// The diagram with every boolean attribute a moddle package declares written as `true` or `false`. A document
// that is not well-formed is handed back as it is, for the import to report.
export function spelledOutBooleans(xml: string, moddle: ModdleTypes): string {
  const document = new DOMParser().parseFromString(xml, "application/xml");
  if (document.getElementsByTagName("parsererror").length > 0) {
    return xml;
  }
  let changed = false;
  for (const element of Array.from(document.getElementsByTagName("*"))) {
    for (const name of booleanAttributes(moddle, element)) {
      const spelled = SPELLED_OUT.get(element.getAttribute(name) ?? "");
      if (spelled !== undefined) {
        element.setAttribute(name, spelled);
        changed = true;
      }
    }
  }
  return changed ? new XMLSerializer().serializeToString(document) : xml;
}
