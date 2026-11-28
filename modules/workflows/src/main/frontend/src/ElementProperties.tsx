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

import { useMemo, useState } from "react";

import { Alert, Stack } from "@mui/material";

import TextInput from "@iap/frontend-commons/inputs/TextInput";
import { fromSingle, singleValue } from "@iap/frontend-commons/inputs/values";
import ValueText from "@iap/frontend-commons/inputs/ValueText";

import { commaSeparated } from "./bpmnParameters";
import { entryEdits, finished, nameEdits, reconciled, snapshotOf, valueEdits } from "./elementEdits";
import ElementTypeFields from "./ElementTypeFields";
import { businessObjectOf, elementName } from "./moddle";
import { PANEL_EDITS_COMMAND } from "./panelEdits";
import ParameterField from "./ParameterField";
import { usePendingWrites } from "./usePendingWrites";
import { isSkippedElement, matchFlowNodeType } from "./workflowTypes";

import type { EditedElement } from "./elementEdits";
import type { Moddle, ModdleElement } from "./moddle";
import type { PanelEdit, PanelEditsContext } from "./panelEdits";
import type { ParameterValue } from "./parameterValues";
import type { Pending } from "./usePendingWrites";
import type { FlowNodeType, Parameter } from "./workflowTypes";
import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";
import type CommandStack from "diagram-js/lib/command/CommandStack";

// One list for every render without a vocabulary, so that matching is not redone for a new empty one
const NO_TYPES: readonly FlowNodeType[] = [];

interface ElementPropertiesProps {
  element: Element;
  viewer: BaseViewer;
  // The vocabulary the element's kind and its editable parameters are read from. Empty until it
  // loads, and after a failure to load it: the name is editable either way.
  types?: readonly FlowNodeType[];
  // Incremented by the parent every time the canvas reports a change to this element, which is the cue
  // to reread it.
  revision?: number;
  readOnly?: boolean;
  // Called as something is typed that is not yet written into the diagram. Until it is, nothing else
  // knows the diagram is about to change, and leaving the page would lose it.
  onDraft?: () => void;
}

// An element the vocabulary does not describe, and the server does not pass over. The server reports it, and
// refuses to activate the process.
function UnrecognizedElementType({ name }: { name: string }) {
  return (
    <>
      <ValueText label="Element Type" values={[ `Not recognized (${name})` ]} />
      <Alert severity="error">The process cannot be activated while it holds this element.</Alert>
    </>
  );
}

// What an element no entry matches is shown as. Without a vocabulary, and for an element the server passes over
// on purpose, that is the name of its BPMN element.
function UnmatchedElementType({ name, types }: { name: string; types: readonly FlowNodeType[] }) {
  return types.length === 0 || isSkippedElement(name)
    ? <ValueText label="Element Type" values={[ name ]} />
    : <UnrecognizedElementType name={name} />;
}

// Display the properties associated with a provided BPMN node.
// Either a series of fields in edit mode, or a plain text output in view mode
export default function ElementProperties(props: ElementPropertiesProps) {
  const {
    element,
    viewer,
    types = NO_TYPES,
    revision = 0,
    readOnly = false,
    onDraft,
  } = props;

  // Worked out once per change to the diagram rather than on every keystroke. bpmn-js edits these objects
  // in place, so the revision is what says they changed.
  const { edited, type } = useMemo(() => {
    const businessObject = businessObjectOf(element);
    const described: EditedElement = {
      element,
      businessObject,
      definitions: (viewer.getDefinitions() ?? {}) as ModdleElement,
      types,
      moddle: () => viewer.get<Moddle>("moddle"),
    };
    return {
      edited: described,
      type: matchFlowNodeType(businessObject, types),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ element, viewer, types, revision ]);

  // What the fields show: the diagram as last read, with what is being typed.
  const [ snapshot, setSnapshot ] = useState(() => snapshotOf(edited, type));

  // Every edit goes through the command stack, as one step however many it writes. A parameter change is
  // then undone by the same keystroke as a move on the canvas, and reported to the panel as a change to
  // this element.
  const apply = (edits: PanelEdit[]) => {
    if (edits.length > 0) {
      viewer.get<CommandStack>("commandStack").execute(PANEL_EDITS_COMMAND, { edits } satisfies PanelEditsContext);
    }
  };

  // What is typed and not written yet is written as the selection moves on, as one step to undo. A change
  // made elsewhere that has not been reread yet is never undone, and a value its field holds back stays so.
  const writePending = (held: Pending) => apply([
    ...held.name ? nameEdits(element, snapshot.name) : [],
    ...type?.parameters.filter(parameter => held.parameters.includes(parameter.name))
      .map(parameter => [ parameter, snapshot.values[parameter.name] ?? [] ] as const)
      .filter(([ parameter, values ]) => finished(parameter, values))
      .flatMap(([ parameter, values ]) => valueEdits(edited, type, parameter, values)) ?? [],
  ]);
  const { pending, holdName, releaseName, holdParameter, releaseParameter } =
    usePendingWrites(viewer, element, writePending);

  // Reread on every revision, this panel's own edits included. The matched kind is watched too, so that a
  // vocabulary arriving after the element was selected fills the fields it brings.
  const [ synced, setSynced ] = useState({ revision, typeName: type?.name });
  if (revision !== synced.revision || type?.name !== synced.typeName) {
    setSynced({ revision, typeName: type?.name });
    setSnapshot(reconciled(snapshot, snapshotOf(edited, type), pending.name));
  }

  const commitName = () => {
    releaseName();
    apply(nameEdits(element, snapshot.name));
  };

  const draftName = (name: string) => {
    onDraft?.();
    holdName();
    setSnapshot({ ...snapshot, name });
  };

  const draftParameter = (parameter: Parameter, values: ParameterValue[]) => {
    onDraft?.();
    holdParameter(parameter.name);
    setSnapshot(shown => ({ ...shown, values: { ...shown.values, [parameter.name]: values } }));
  };

  const commitParameter = (matched: FlowNodeType, parameter: Parameter, values: ParameterValue[]) => {
    releaseParameter(parameter.name);
    apply(valueEdits(edited, matched, parameter, values));
  };

  // Built from a kind that is known to have matched, so that every field's handler knows which entry
  // it is writing a parameter of.
  const parameterFields = (matched: FlowNodeType) => matched.parameters.map(parameter => (
    <ParameterField
      key={parameter.name}
      parameter={parameter}
      values={snapshot.values[parameter.name] ?? []}
      commaSeparated={commaSeparated(matched, parameter)}
      readOnly={readOnly}
      onChange={values => draftParameter(parameter, values)}
      onCommit={values => commitParameter(matched, parameter, values)}
    />
  ));

  const switchTo = (chosen: FlowNodeType) => apply(entryEdits(edited, type, chosen));

  return (
    <Stack spacing={2} key={ element.id }>
      <ValueText label="Identifier" values={[ element.id ]} code />

      <TextInput
        label="Name"
        values={fromSingle(snapshot.name)}
        readOnly={readOnly}
        onChange={values => draftName(singleValue(values))}
        onCommit={commitName}
      />

      {
        type === undefined
          ? <UnmatchedElementType name={elementName(edited.businessObject)} types={types} />
          : <ElementTypeFields type={type} types={types} readOnly={readOnly} onSwitch={switchTo} />
      }

      { type && parameterFields(type) }
    </Stack>
  );
}
