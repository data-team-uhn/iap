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
  useCallback,
  useState,
} from "react";

import {
  Typography,
} from "@mui/material";

import ElementProperties from "./ElementProperties";

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
  // When true, the selected element's properties are shown as text rather than in editable fields.
  readOnly?: boolean;
}

export default function PropertiesPanel(props: PropertiesPanelProps) {
  const {
    viewer,
    readOnly = false,
  } = props;

  const [selectedElements, setSelectedElements] = useState<Element[]>([]);
  const [element, setElement] = useState<Element | null>(null);
  // Counts reported changes to the selected element. bpmn-js edits an element in place and hands back the very same
  // object, so this counter -- not a comparison of elements -- is what tells ElementProperties to reread its name.
  const [revision, setRevision] = useState(0);

  const handleSelectionChanged = useCallback(
    (event: SelectionChangedEvent) => {
      setSelectedElements(event.newSelection);
      setElement(event.newSelection[0]);
    }, []
  );

  const handleElementsChanged = (event: ElementsChangedEvent) => {
    if (!element || event.elements.length === 0) {
      return;
    }

    for (const newElement of event.elements) {
      if (element.id === newElement.id) {
        setElement(newElement);
        setRevision(current => current + 1);
        break;
      }
    }
  };

  useEffect(() => {
    if (viewer) {
      viewer.on('selection.changed', handleSelectionChanged);
      viewer.on('elements.changed', handleElementsChanged);
    }
    // handleSelectionChanged/handleElementsChanged excluded: element?.id already covers what they'd resubscribe on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewer, element?.id]);

  return (
    <div>
      {
        selectedElements.length === 1 && element && viewer
          && <ElementProperties
            // Remount on element change so ElementProperties' local name state resets.
            key={ element.id }
            viewer={ viewer }
            element={ element }
            revision={ revision }
            readOnly={ readOnly }
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
