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

import ArchiveOutlinedIcon from "@mui/icons-material/ArchiveOutlined";

import { EventAction } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import { pathOf, titleOf } from "./schemaModel";

import type { SchemaActionProps } from "./SchemaActions";

// Closes a schema as a whole, which retires every version with it.
function SchemaRetireAction({ schema, reload }: SchemaActionProps) {
  if (!offers(schema, "retire")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(schema)}
      reload={reload}
      icon={<ArchiveOutlinedIcon fontSize="small" />}
      label="Retire"
      event="retire"
      color="warning"
      title={`Retire ${titleOf(schema)}`}
      explanation={"None of its versions will accept new submissions until it is reopened. Existing "
        + "submissions keep using them."}
      done={`${titleOf(schema)} is retired`}
    />
  );
}

export default SchemaRetireAction;
