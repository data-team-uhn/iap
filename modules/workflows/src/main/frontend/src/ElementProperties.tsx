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
} from "react";

import {
  TextField,
  Typography,
} from "@mui/material";

import type BaseViewer from "bpmn-js/lib/BaseViewer";
import type BpmnModeling from "bpmn-js/lib/features/modeling/Modeling";
import type { Element } from "bpmn-js/lib/model/Types";

interface ElementPropertiesProps {
  element: Element;
  // The canvas the element belongs to. Only a Modeler can be asked to change it, which is why
  // editing is not offered when the properties are read-only.
  viewer: BaseViewer;
  readOnly?: boolean;
}

export default function ElementProperties(props: ElementPropertiesProps) {
  const {
    element,
    viewer,
    readOnly = false
  } = props;

  useEffect(() => {
    // Do nothing: need a useEffect on element to trigger a re-render
  }, [element])

  const updateName = (name: string) => {
    const modeling = viewer.get<BpmnModeling>('modeling');
    modeling.updateLabel(element, name);
  }

  const name = (element.businessObject as { name?: string } | undefined)?.name ?? "";

  return (
    <div key={ element.id }>
      <Typography>Identifier: {element.id}</Typography>

      {
        readOnly
          ? <Typography>Name: {name}</Typography>
          : (
            <Typography>
              Name:
              <TextField
                value={name}
                onChange={(event) => updateName(event.target.value)}
              />
            </Typography>
          )
      }
    </div>
  );
}
