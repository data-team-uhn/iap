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
  useRef,
  useState,
} from "react";

import {
  Typography,
} from "@mui/material";

import ElementProperties from "./ElementProperties";

import type { FlowNodeType } from "./workflowTypes";
import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type { Element } from "bpmn-js/lib/model/Types";


interface SelectionChangedEvent {
  newSelection: Element[];
}

interface ElementsChangedEvent {
  elements: Element[];
}

interface PropertiesPanelProps {
  // The canvas whose selection is being followed: a Modeler in edit mode, a plain viewer otherwise.
  viewer: BaseViewer | null;
  // The vocabulary the selected element's kind and parameters are read from.
  types?: readonly FlowNodeType[];
  // When true, the selected element's properties are shown as text rather than in editable fields.
  readOnly?: boolean;
  // Called as something is typed that is not yet written into the diagram.
  onDraft?: () => void;
}

export default function PropertiesPanel(props: PropertiesPanelProps) {
  const {
    viewer,
    types,
    readOnly = false,
    onDraft,
  } = props;

  const [selectedElements, setSelectedElements] = useState<Element[]>([]);
  const [element, setElement] = useState<Element | null>(null);
  // Counts reported changes to the selected element. bpmn-js edits an element in place and hands back the same
  // object, so a comparison of elements would see no change. This counter tells ElementProperties to reread it.
  const [revision, setRevision] = useState(0);

  // What the change handler compares against, kept out of the subscription's dependencies. The canvas
  // outlives many selections, and re-subscribing on each one would leave the previous subscription behind.
  // Set where the selection is, so that a change reported before the next render is compared against it.
  const selected = useRef<Element | null>(null);

  useEffect(() => {
    if (!viewer) {
      return undefined;
    }
    // A label stands for the element it labels
    const select = (chosen: Element | null) => {
      const labelled = (chosen?.labelTarget as Element | undefined) ?? chosen;
      selected.current = labelled;
      setElement(labelled);
    };
    // ElementProperties writes what is still being typed into the element being left before this runs
    const selectionChanged = (event: SelectionChangedEvent) => {
      setSelectedElements(event.newSelection);
      select(event.newSelection[0] ?? null);
    };
    const elementsChanged = (event: ElementsChangedEvent) => {
      const current = selected.current;
      const changed = current && event.elements.find(candidate => candidate.id === current.id);
      if (changed) {
        select(changed);
        setRevision(revisions => revisions + 1);
      }
    };
    viewer.on('selection.changed', selectionChanged);
    viewer.on('elements.changed', elementsChanged);
    return () => {
      viewer.off('selection.changed', selectionChanged);
      viewer.off('elements.changed', elementsChanged);
      // Whatever was selected belonged to the canvas going away, and is not in the one replacing it
      setSelectedElements([]);
      select(null);
    };
  }, [viewer]);

  return (
    <div>
      {
        selectedElements.length === 1 && element && viewer
          && <ElementProperties
            // A new element starts fresh: its values read anew, with nothing typed and nothing pending
            key={ element.id }
            viewer={ viewer }
            element={ element }
            types={ types }
            revision={ revision }
            readOnly={ readOnly }
            onDraft={ onDraft }
          />
      }

      {
        selectedElements.length === 0
          && <Typography>Please select an element.</Typography>
      }

      {
        selectedElements.length > 1
          && <Typography>Please select a single element.</Typography>
      }
    </div>
  );
}
