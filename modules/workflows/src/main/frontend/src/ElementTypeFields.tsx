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

// The fields that say what kind of element the selected one is, and change it: its BPMN element type, and the
// entry of the vocabulary it means within that type.

import { useMemo } from "react";

import type { Choice } from "@iap/frontend-commons/inputs/choices";
import ChoiceSearch from "@iap/frontend-commons/inputs/ChoiceSearch";
import ChoiceSelect from "@iap/frontend-commons/inputs/ChoiceSelect";
import { fromSingle, singleValue } from "@iap/frontend-commons/inputs/values";

import { elementTypeOf, entriesOf, isReachable, switchableTypes, typeGroupOf } from "./workflowTypes";

import type { FlowNodeType } from "./workflowTypes";

interface ElementTypeFieldsProps {
  // The entry the element means
  type: FlowNodeType;
  types: readonly FlowNodeType[];
  readOnly: boolean;
  // Called with the entry the element is to mean. Choosing an element type chooses its default entry.
  onSwitch: (chosen: FlowNodeType) => void;
}

// The choices one field offers, each with the entry it makes the element mean.
interface FieldChoices {
  choices: Choice[];
  entries: ReadonlyMap<string, FlowNodeType>;
}

// The element types the element can be changed to. The select offers the element's own as well, as it does any
// value it holds that is not listed.
function elementTypeOptions(type: FlowNodeType, types: readonly FlowNodeType[]): FieldChoices {
  const entries = switchableTypes(type, types);
  return { choices: [ ...entries.keys() ].map(name => ({ value: name, label: name })), entries };
}

// The entries of the element's type a change can make it mean, its own among them.
function kindOptions(type: FlowNodeType, types: readonly FlowNodeType[]): FieldChoices {
  const kinds = entriesOf(types, elementTypeOf(type))
    .filter(kind => kind.name === type.name || isReachable(kind, types));
  return {
    choices: kinds.map(kind => ({ value: kind.name, label: kind.label })),
    entries: new Map(kinds.map(kind => [ kind.name, kind ])),
  };
}

// The heading each entry is listed under, or none when they all share one.
function groupsOf(kinds: FieldChoices): ((name: string) => string) | undefined {
  const groups = new Map([ ...kinds.entries ].map(([ name, kind ]) => [ name, typeGroupOf(kind) ]));
  return new Set(groups.values()).size > 1 ? name => groups.get(name) ?? "" : undefined;
}

export default function ElementTypeFields({ type, types, readOnly, onSwitch }: ElementTypeFieldsProps) {
  const elementType = elementTypeOf(type);
  const { elementTypes, kinds, groupOf } = useMemo(() => {
    const offered = kindOptions(type, types);
    return { elementTypes: elementTypeOptions(type, types), kinds: offered, groupOf: groupsOf(offered) };
  }, [ types, type ]);

  const switchThrough = (entries: ReadonlyMap<string, FlowNodeType>) => (values: string[]) => {
    const chosen = entries.get(singleValue(values));
    if (chosen) {
      onSwitch(chosen);
    }
  };

  return (
    <>
      <ChoiceSelect
        label="Element Type"
        values={fromSingle(elementType)}
        choices={elementTypes.choices}
        allowNone={false}
        readOnly={readOnly || elementTypes.choices.length < 2}
        onChange={switchThrough(elementTypes.entries)}
      />
      {
        kinds.choices.length > 1 && (
          <ChoiceSearch
            label={`${elementType} Type`}
            values={fromSingle(type.name)}
            choices={kinds.choices}
            groupOf={groupOf}
            required
            readOnly={readOnly}
            onChange={switchThrough(kinds.entries)}
          />
        )
      }
    </>
  );
}
