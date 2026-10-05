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

import { EventAction } from "@iap/frontend-commons/components/EventAction";

import { offers, pathOf, titleOf } from "./schemaModel";

import type { SchemaActionProps } from "./SchemaActions";

// Deletes a schema with all its versions, unless something refers to them.
function SchemaDiscardAction({ schema, reload, removed }: SchemaActionProps) {
  if (!offers(schema, "discard")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(schema)}
      reload={removed ?? reload}
      icon={<DeleteOutlinedIcon fontSize="small" />}
      label="Discard"
      event="discard"
      color="error"
      title={`Discard ${titleOf(schema)}`}
      explanation="It will be deleted with all its versions, unless something refers to them."
      done={`${titleOf(schema)} is discarded`}
    />
  );
}

export default SchemaDiscardAction;
