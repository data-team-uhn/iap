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

// What the properties panel shows of one element, and the edits that write what was typed back into it.
// Pure, so that each rule can be checked without a panel.

import { parameterEdit, readValues, typeEdit } from "./bpmnParameters";
import { businessObjectOf, nameOf } from "./moddle";
import { sameValues, settled, usableValues } from "./parameterValues";
import { matchFlowNodeType, replacementFor } from "./workflowTypes";

import type { Moddle, ModdleElement } from "./moddle";
import type { PanelEdit } from "./panelEdits";
import type { ParameterValue } from "./parameterValues";
import type { FlowNodeType, Parameter } from "./workflowTypes";
import type { Element } from "bpmn-js/lib/model/Types";

// Everything read off the diagram at once, so that one report of a change re-reads one object rather
// than each field having to notice for itself.
export interface Snapshot {
  name: string;
  values: Record<string, ParameterValue[] | undefined>;
}

// The element being edited, and what an edit of it is built from.
export interface EditedElement {
  element: Element;
  businessObject: ModdleElement;
  // The prefix the document binds the IAP namespace to is declared here, so every read and write of a
  // parameter needs the document as well as the element.
  definitions: ModdleElement;
  types: readonly FlowNodeType[];
  // Asked for only when an edit is built, which a viewer showing the diagram never does
  moddle: () => Moddle;
}

export function snapshotOf(edited: EditedElement, type: FlowNodeType | undefined): Snapshot {
  const { element, businessObject, definitions } = edited;
  return {
    name: nameOf(element),
    values: type === undefined ? {} : Object.fromEntries(type.parameters
      .map(parameter => [ parameter.name, readValues(businessObject, definitions, type, parameter) ] as const)),
  };
}

// What the fields should show once the diagram has been reread. A name still being typed is kept. A value the
// diagram holds the same once trimmed is kept as typed. Reading trims, so rereading after the panel's own edit
// would otherwise swallow a space the moment it was typed. Anything else is a change made elsewhere, and wins.
export function reconciled(shown: Snapshot, reread: Snapshot, typingName: boolean): Snapshot {
  return {
    name: typingName ? shown.name : reread.name,
    values: Object.fromEntries(Object.entries(reread.values).map(([ name, values = [] ]) => {
      const typed = shown.values[name];
      return [ name, typed !== undefined && sameValues(settled(typed), values) ? typed : values ];
    })),
  };
}

// The edit that renames the element. None when the name is the one it has: leaving a field untouched writes
// nothing, or every visit to it would leave an empty step to undo.
export const nameEdits = (element: Element, name: string): PanelEdit[] =>
  name === nameOf(element) ? [] : [ { element, label: name } ];

// The edits that give a parameter these values. A field is built for the entry the element meant when it
// rendered. After an undo it may mean another, whose parameters the value is not one of, and nothing is written.
export function valueEdits(
  edited: EditedElement,
  matched: FlowNodeType,
  parameter: Parameter,
  values: ParameterValue[]
): PanelEdit[] {
  const { element, businessObject, definitions, types, moddle } = edited;
  if (matchFlowNodeType(businessObject, types)?.name !== matched.name
    || sameValues(settled(values), readValues(businessObject, definitions, matched, parameter))) {
    return [];
  }
  return parameterEdit(moddle(), businessObject, definitions, matched, parameter, values)
    .map(edit => ({ element, ...edit }));
}

// Whether every value typed is one the server would store. A field holds back one it would not, such as a
// long's lone sign, so it is held back however the panel is left too.
export const finished = (parameter: Parameter, values: ParameterValue[]): boolean =>
  usableValues(parameter, values).length === settled(values).length;

// The edits that make the element mean another entry. An entry that needs another BPMN element, or another
// event in it, redraws the element first. The extension element that names the entry is then written to the
// redrawn one, in the same step, with the IAP markup the element held before.
export function entryEdits(edited: EditedElement, from: FlowNodeType | undefined, chosen: FlowNodeType): PanelEdit[] {
  const { element, businessObject, definitions, moddle } = edited;
  const written = (target: Element): PanelEdit[] =>
    typeEdit(moddle(), businessObjectOf(target), definitions, from, chosen, businessObject)
      .map(edit => ({ element: target, ...edit }));
  const replaceWith = replacementFor(businessObject, chosen);
  return replaceWith ? [ { element, replaceWith, then: written } ] : written(element);
}
