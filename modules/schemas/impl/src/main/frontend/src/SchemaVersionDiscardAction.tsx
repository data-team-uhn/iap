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

import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";

import { EventAction } from "./EventAction";
import { labelOf, offers, pathOf } from "./schemaModel";

import type { SchemaVersionActionProps } from "./SchemaVersionActions";

// Deletes a version, unless something refers to it: what is in use is retired instead.
function SchemaVersionDiscardAction(props: SchemaVersionActionProps) {
  const { version } = props;
  if (!offers(version, "discard")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(version)}
      reload={props.removed ?? props.reload}
      report={props.report}
      icon={<DeleteOutlinedIcon fontSize="small" />}
      label="Discard"
      event="discard"
      color="error"
      title={`Discard version ${labelOf(version)}`}
      explanation="This version and everything in it will be deleted, unless something refers to it."
      done={`Version ${labelOf(version)} is discarded`}
    />
  );
}

export default SchemaVersionDiscardAction;
