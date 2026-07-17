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
} from "react";

import {
  TextField,
  Typography,
} from "@mui/material";

export default function ElementProperties(props) {
  const {
    element,
    modeler
  } = props;

  useEffect(() => {
    // Do nothing: need a useEffect on element to trigger a re-render
  }, [element])

  let updateName = (name) => {
    const modeling = modeler.get('modeling');
    modeling.updateLabel(element, name);
  }

  return (
    <div key={ element.id }>
      <Typography>Identifier: {element.id}</Typography>

      <Typography>
        Name:
        <TextField
          value={element?.businessObject?.name}
          onChange={(event) => updateName(event.target.value)}
        />
      </Typography>
    </div>
  );
}
