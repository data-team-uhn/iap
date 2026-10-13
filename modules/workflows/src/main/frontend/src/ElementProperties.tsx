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
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import { commaSeparated, parameterEdit, readValues, typeEdit } from "./bpmnParameters";
import { PANEL_EDITS } from "./panelEdits";
import ParameterField from "./ParameterField";
import { sameValues, settled } from "./parameterValues";
import { commitProps, noneItem } from "./parameterWidget";
import { matchFlowNodeType, selectableTypes } from "./workflowTypes";

import type { Moddle, ModdleElement } from "./moddle";
import type { PanelEdit, PanelEditsContext } from "./panelEdits";
import type { ParameterValue } from "./parameterValues";
import type { FlowNodeType, Parameter } from "./workflowTypes";
import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";
import type CommandStack from "diagram-js/lib/command/CommandStack";
import type ElementRegistry from "diagram-js/lib/core/ElementRegistry";

// Everything read off the diagram at once, so that one report of a change re-reads one object rather
// than each field having to notice for itself.
interface Snapshot {
  name: string;
  values: Record<string, ParameterValue[] | undefined>;
}

const nameOf = (element: Element) => (element.businessObject as { name?: string } | undefined)?.name ?? "";

// The type allows an element with no business object of its own, though no diagram has one. It has no
// name, no kind and no parameters, and an empty object says all three.
const objectOf = (element: Element) => (element.businessObject ?? {}) as ModdleElement;

function snapshotOf(
  element: Element,
  businessObject: ModdleElement,
  definitions: ModdleElement,
  type: FlowNodeType | undefined
): Snapshot {
  return {
    name: nameOf(element),
    values: type === undefined ? {} : Object.fromEntries(type.parameters
      .map(parameter => [ parameter.name, readValues(businessObject, definitions, type, parameter) ] as const)),
  };
}

// What the fields should show once the diagram has been reread. A value the diagram holds the same
// once trimmed is kept as typed. Reading trims, so rereading after the panel's own edit would otherwise
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

// What has been typed and not yet written: whether the name has, and which parameters by name.
interface Pending {
  name: boolean;
  parameters: string[];
}

// Ahead of the listeners subscribed at the default priority. Those are the selection letting go of a
// removed element, and the panel moving on to the next selection.
const FIRST = 1500;

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

  // What is typed and not written yet, so that only that is written when the panel moves on. A change
  // made elsewhere that has not been reread yet is never undone.
  const [ pending, setPending ] = useState<Pending>({ name: false, parameters: [] });

  // Every edit goes through the command stack, as one step however many it writes. A parameter change is
  // then undone by the same keystroke as a move on the canvas, and reported to the panel as a change to
  // this element.
  const apply = (edits: (PanelEdit | undefined)[]) => {
    const written = edits.filter(edit => edit !== undefined);
    if (written.length > 0) {
      viewer.get<CommandStack>("commandStack").execute(PANEL_EDITS, { edits: written } satisfies PanelEditsContext);
    }
  };

  // What is typed stays in the panel until the field is finished with. Leaving a field untouched writes
  // nothing, or every visit to it would leave an empty step to undo.
  const nameEdit = (): PanelEdit | undefined =>
    snapshot.name === nameOf(element) ? undefined : { element, label: snapshot.name };

  const commitName = () => {
    setPending(held => ({ ...held, name: false }));
    apply([ nameEdit() ]);
  };

  const draftName = (name: string) => {
    onDraft?.();
    setPending(held => ({ ...held, name: true }));
    setSnapshot({ ...snapshot, name });
  };

  const draftParameter = (parameter: Parameter, values: ParameterValue[]) => {
    onDraft?.();
    setPending(held => held.parameters.includes(parameter.name) ? held
      : { ...held, parameters: [ ...held.parameters, parameter.name ] });
    setSnapshot(shown => ({ ...shown, values: { ...shown.values, [parameter.name]: values } }));
  };

  // A field is built for the entry the element meant when it rendered. After an undo it may mean
  // another, whose parameters the value is not one of.
  const valueEdit = (matched: FlowNodeType, parameter: Parameter, values: ParameterValue[]): PanelEdit | undefined => {
    if (matchFlowNodeType(businessObject, types)?.name !== matched.name
      || sameValues(settled(values), readValues(businessObject, definitions, matched, parameter))) {
      return undefined;
    }
    const edit = parameterEdit(viewer.get<Moddle>("moddle"), businessObject, definitions, matched, parameter, values);
    return edit && { element, ...edit };
  };

  const commitParameter = (matched: FlowNodeType, parameter: Parameter, values: ParameterValue[]) => {
    setPending(held => ({ ...held, parameters: held.parameters.filter(name => name !== parameter.name) }));
    apply([ valueEdit(matched, parameter, values) ]);
  };

  // Set once the element is going: its canvas destroyed or cleared, or the element removed, by an undo
  // of its creation for one. There is then nothing left to write into.
  const gone = useRef(false);

  // Selecting another element need not leave the field being typed in, so what is pending is written as
  // the selection changes, as one step to undo.
  const writePending = useRef<() => void>(() => undefined);
  useEffect(() => {
    writePending.current = () => {
      if (!pending.name && pending.parameters.length === 0) {
        return;
      }
      setPending({ name: false, parameters: [] });
      if (gone.current || viewer.get<ElementRegistry>("elementRegistry").get(element.id) !== element) {
        return;
      }
      apply([
        ...pending.name ? [ nameEdit() ] : [],
        ...type?.parameters.filter(parameter => pending.parameters.includes(parameter.name))
          .map(parameter => valueEdit(type, parameter, snapshot.values[parameter.name] ?? [])) ?? [],
      ]);
    };
  });

  // Replacing an element keeps its id, so the panel is handed the replacement without remounting
  useEffect(() => {
    gone.current = false;
    const leave = () => {
      gone.current = true;
    };
    const removed = (event: { element: Element }) => {
      if (event.element === element) {
        leave();
      }
    };
    const selectionChanged = () => writePending.current();
    viewer.on([ "diagram.destroy", "diagram.clear" ], FIRST, leave);
    viewer.on([ "shape.remove", "connection.remove" ], FIRST, removed);
    viewer.on("selection.changed", FIRST, selectionChanged);
    return () => {
      viewer.off([ "diagram.destroy", "diagram.clear" ], leave);
      viewer.off([ "shape.remove", "connection.remove" ], removed);
      viewer.off("selection.changed", selectionChanged);
    };
  }, [ viewer, element ]);

  // Built from a kind that is known to have matched, so that every field's handler knows which entry
  // it is writing a parameter of.
  const parameterFields = (matched: FlowNodeType) => matched.parameters.map(parameter => (
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
      apply([ { element, ...typeEdit(viewer.get<Moddle>("moddle"), businessObject, definitions, type, chosen) } ]);
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
                onChange={event => draftName(event.target.value)}
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
