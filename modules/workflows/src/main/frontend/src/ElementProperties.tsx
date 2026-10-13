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

import {
  useMemo,
  useState,
} from "react";

import {
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import { commaSeparated, parameterEdit, readValues, typeEdit } from "./bpmnParameters";
import ParameterField from "./ParameterField";
import { commitProps, noneItem } from "./parameterWidget";
import { isStructure, matchFlowNodeType, selectableTypes } from "./workflowTypes";

import type { ParameterEdit } from "./bpmnParameters";
import type { Moddle, ModdleElement } from "./moddle";
import type { FlowNodeType, Parameter } from "./workflowTypes";
import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type BpmnModeling from "bpmn-js/lib/features/modeling/Modeling";
import type { Element } from "bpmn-js/lib/model/Types";

// Everything read off the diagram at once, so that one report of a change re-reads one object rather
// than each field having to notice for itself.
interface Snapshot {
  name: string;
  values: Record<string, string[] | undefined>;
}

const nameOf = (element: Element) => (element.businessObject as { name?: string } | undefined)?.name ?? "";

// The type allows an element with no business object of its own, though no diagram has one. It has no
// name, no kind and no parameters, and an empty object says all three.
const objectOf = (element: Element) => (element.businessObject ?? {}) as ModdleElement;

// The parameters the panel offers a field for: every one but a structure, which holds records rather than
// text.
const editable = (type: FlowNodeType): Parameter[] => type.parameters.filter(parameter => !isStructure(parameter));

function snapshotOf(
  element: Element,
  businessObject: ModdleElement,
  definitions: ModdleElement,
  type: FlowNodeType | undefined
): Snapshot {
  return {
    name: nameOf(element),
    values: type === undefined ? {} : Object.fromEntries(editable(type)
      .map(parameter => [ parameter.name, readValues(businessObject, definitions, type, parameter) ] as const)),
  };
}

// What the server reads out of values being edited: each trimmed, and a blank one dropped.
const settled = (values: readonly string[]) => values.map(value => value.trim()).filter(Boolean);

const sameValues = (left: readonly string[], right: readonly string[]) =>
  left.length === right.length && left.every((value, index) => value === right[index]);

// What the fields should show once the diagram has been reread. A value the diagram holds the same
// once trimmed is kept as typed: reading trims, so rereading after the panel's own edit would otherwise
// swallow a space the moment it was typed. Anything else is a change made elsewhere, and wins.
function reconciled(shown: Snapshot, reread: Snapshot): Snapshot {
  return {
    name: reread.name,
    values: Object.fromEntries(Object.entries(reread.values).map(([ name, values = [] ]) => {
      const typed = shown.values[name];
      return [ name, typed !== undefined && sameValues(settled(typed), values) ? typed : values ];
    })),
  };
}

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
}

// Display the properties associated with a provided BPMN node.
// Either a series of fields in edit mode, or a plain text output in view mode
export default function ElementProperties(props: ElementPropertiesProps) {
  const {
    element,
    viewer,
    types = NO_TYPES,
    revision = 0,
    readOnly = false
  } = props;

  // Worked out once per change to the diagram rather than on every keystroke. bpmn-js edits these objects
  // in place, so the revision is what says they changed.
  const { businessObject, definitions, selectable, type } = useMemo(() => {
    const object = objectOf(element);
    return {
      businessObject: object,
      // The prefix the document binds the IAP namespace to is declared here, so every read and write of a
      // parameter needs the document as well as the element.
      definitions: (viewer.getDefinitions() ?? {}) as ModdleElement,
      selectable: selectableTypes(object, types),
      type: matchFlowNodeType(object, types),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ element, viewer, types, revision ]);

  // Local copy: bpmn-js mutates the businessObject in place, so it wouldn't otherwise trigger a
  // re-render.
  const [ snapshot, setSnapshot ] = useState(() => snapshotOf(element, businessObject, definitions, type));

  // That mutation leaves the element object itself unchanged, so a change made elsewhere shows only as a
  // bumped revision. Every one is reread, this panel's own edits included. The matched kind is watched
  // too, so that a vocabulary arriving after the element was selected fills the fields it brings.
  const [ synced, setSynced ] = useState({ revision, typeName: type?.name });
  if (revision !== synced.revision || type?.name !== synced.typeName) {
    setSynced({ revision, typeName: type?.name });
    setSnapshot(reconciled(snapshot, snapshotOf(element, businessObject, definitions, type)));
  }

  const modeling = () => viewer.get<BpmnModeling>("modeling");

  // Every edit goes through the command stack. A parameter change is then undone by the same keystroke as
  // a move on the canvas, and reported to the panel as a change to this element.
  const applyEdit = (edit: ParameterEdit | undefined) => {
    if (edit !== undefined) {
      modeling().updateModdleProperties(element, edit.moddleElement, edit.properties);
    }
  };

  // What is typed stays in the panel until the field is finished with. Leaving a field untouched writes
  // nothing, or every visit to it would leave an empty step to undo.
  const commitName = () => {
    if (snapshot.name !== nameOf(element)) {
      modeling().updateLabel(element, snapshot.name);
    }
  };

  const draftParameter = (parameter: Parameter, values: string[]) =>
    setSnapshot(shown => ({ ...shown, values: { ...shown.values, [parameter.name]: values } }));

  const commitParameter = (matched: FlowNodeType, parameter: Parameter, values: string[]) => {
    if (!sameValues(settled(values), readValues(businessObject, definitions, matched, parameter))) {
      applyEdit(parameterEdit(viewer.get<Moddle>("moddle"), businessObject, definitions, matched, parameter, values));
    }
  };

  // Built from a kind that is known to have matched, so that every field's handler knows which entry
  // it is writing a parameter of.
  const parameterFields = (matched: FlowNodeType) => editable(matched).map(parameter => (
    <ParameterField
      key={parameter.name}
      parameter={parameter}
      values={snapshot.values[parameter.name] ?? []}
      // A list kept in one attribute is joined with commas, so a comma typed into a value splits it
      commaSeparated={commaSeparated(matched, parameter)}
      readOnly={readOnly}
      onChange={values => draftParameter(parameter, values)}
      onCommit={values => commitParameter(matched, parameter, values)}
    />
  ));

  const changeType = (name: string) => {
    const chosen = selectable.find(candidate => candidate.name === name);
    if (chosen) {
      applyEdit(typeEdit(viewer.get<Moddle>("moddle"), businessObject, definitions, type, chosen));
    }
  };

  // Only worth asking for when the element could be read as more than one thing. With a single candidate
  // the kind is reported rather than offered.
  const choosable = !readOnly && selectable.length > 1;

  return (
    <Stack spacing={2} key={ element.id }>
      <Typography>Identifier: {element.id}</Typography>

      {
        readOnly
          ? <Typography>Name: {snapshot.name}</Typography>
          : (
            <Typography>
              Name:
              <TextField
                value={snapshot.name}
                onChange={event => setSnapshot({ ...snapshot, name: event.target.value })}
                {...commitProps(commitName)}
              />
            </Typography>
          )
      }

      {
        choosable
          ? (
            <TextField
              select
              fullWidth
              label="Type"
              value={type?.name ?? ""}
              onChange={event => changeType(event.target.value)}
            >
              { type === undefined && noneItem() }
              {
                selectable.map(candidate => (
                  <MenuItem key={candidate.name} value={candidate.name}>{candidate.label}</MenuItem>
                ))
              }
            </TextField>
          )
          : type !== undefined && <Typography>Type: {type.label}</Typography>
      }

      { type && parameterFields(type) }
    </Stack>
  );
}
