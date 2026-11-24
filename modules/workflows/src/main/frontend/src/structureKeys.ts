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

// The records of a repeated structure the server drops for their key, and finding them in a whole diagram.

import { readValues } from "./bpmnParameters";
import { isRecordValue, recordValues, settled, textValues, valuesOf } from "./parameterValues";
import { fieldsOf, isMultiple, isStructure, matchFlowNodeType } from "./workflowTypes";

import type { ModdleElement } from "./moddle";
import type { ParameterValue, StructureRecord } from "./parameterValues";
import type { FlowNodeType, Parameter } from "./workflowTypes";

// What is read of a diagram element: bpmn-js's own type, less what it holds only for drawing.
interface DiagramElement {
  id: string;
  businessObject?: unknown;
}

export type KeyProblem = "blank" | "repeated";

// The key of a record, trimmed as the server compares it.
export function keyOf(parameter: Parameter, record: StructureRecord): string {
  const key = parameter.keyParameter === undefined ? undefined
    : textValues(valuesOf(record, parameter.keyParameter)).at(0);
  return (key ?? "").trim();
}

// What is wrong with the key of each record, in order. The server keeps the first record of a key and drops
// a later one, and drops a record that has content but no key. A record with nothing in it is a draft, not a
// problem, and a structure with no key parameter has no key to be wrong.
export function keyProblems(parameter: Parameter, records: readonly StructureRecord[]): (KeyProblem | undefined)[] {
  const keys = records.map(record => keyOf(parameter, record));
  return records.map((record, index): KeyProblem | undefined => {
    if (parameter.keyParameter === undefined) {
      return undefined;
    }
    if (keys[index] === "") {
      return settled([ record ]).length > 0 ? "blank" : undefined;
    }
    return keys.indexOf(keys[index]) < index ? "repeated" : undefined;
  });
}

// The problems of the structures held in one parameter's values, and of those inside their records.
function parameterProblems(parameter: Parameter, values: readonly ParameterValue[], where: string): string[] {
  if (!isStructure(parameter)) {
    return [];
  }
  const records = recordValues(values);
  const own = isMultiple(parameter) ? keyProblems(parameter, records).flatMap((problem, index) => {
    const key = keyOf(parameter, records[index]);
    return problem === "blank" ? [ `${where}: a ${parameter.label} has no key.` ]
      : problem === "repeated" ? [ `${where}: the key "${key}" of ${parameter.label} is already in use.` ] : [];
  }) : [];
  const inner = records.flatMap(record => fieldsOf(parameter).flatMap(field => {
    const held = valuesOf(record, field.name).filter(isRecordValue);
    const named = keyOf(parameter, record);
    return parameterProblems(field, held, `${where}, ${parameter.label} ${named === "" ? "without a key" : `"${named}"`}`);
  }));
  return [ ...own, ...inner ];
}

// A line for every record in the diagram the server would drop for its key, naming the element, the
// parameter and the key. Empty when there is none.
export function diagramKeyProblems(
  elements: readonly DiagramElement[],
  definitions: ModdleElement,
  types: readonly FlowNodeType[]
): string[] {
  return elements.flatMap(element => {
    const businessObject = (element.businessObject ?? {}) as ModdleElement;
    const type = matchFlowNodeType(businessObject, types);
    const name = (businessObject as { name?: string }).name?.trim() ?? "";
    return type === undefined ? [] : type.parameters.flatMap(parameter =>
      parameterProblems(
        parameter,
        readValues(businessObject, definitions, type, parameter),
        name === "" ? element.id : name
      ));
  });
}
