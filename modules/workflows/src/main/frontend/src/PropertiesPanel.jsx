//
//  Licensed to the Apache Software Foundation (ASF) under one
//  or more contributor license agreements.  See the NOTICE file
//  distributed with this work for additional information
//  regarding copyright ownership.  The ASF licenses this file
//  to you under the Apache License, Version 2.0 (the
//  "License"); you may not use this file except in compliance
//  with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing,
//  software distributed under the License is distributed on an
//  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//  KIND, either express or implied.  See the License for the
//  specific language governing permissions and limitations
//  under the License.
//

import {
  useEffect,
  useCallback,
  useState,
} from "react";

import {
  Typography,
} from "@mui/material";

import ElementProperties from "./ElementProperties";

export default function PropertiesPanel (props) {
  const {
    modeler
  } = props;

  const [selectedElements, setSelectedElements] = useState([]);
  const [element, setElement] = useState(null);

  const handleSelectionChanged = useCallback(
    (event) => {
      setSelectedElements(event.newSelection);
      setElement(event.newSelection[0]);
    }, []
  );

  const handleElementsChanged = (event) => {
    if (!element || !event?.elements?.length > 0) {
      return;
    }

    for (let i = 0; i < event.elements.length; i++) {
      const newElement = event.elements[i];
      if (element.id === newElement.id) {
        setElement(newElement);
        break;
      }
    }
  };

  useEffect(() => {
    if (modeler) {
      modeler.on('selection.changed', handleSelectionChanged);
      modeler.on('elements.changed', handleElementsChanged);
    }
  }, [modeler, element?.id]);

  return (
    <div>
      {
        selectedElements.length === 1 && element
          && <ElementProperties modeler={ modeler } element={ element } />
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
