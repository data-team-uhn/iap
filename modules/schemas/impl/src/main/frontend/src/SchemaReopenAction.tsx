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

import RestoreOutlinedIcon from "@mui/icons-material/RestoreOutlined";

import { EventAction } from "./EventAction";
import { offers, pathOf, titleOf } from "./schemaModel";

import type { SchemaActionProps } from "./SchemaActions";

// Reopens a retired schema, which brings each version back to where it stood.
function SchemaReopenAction({ schema, reload, report }: SchemaActionProps) {
  if (!offers(schema, "activate")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(schema)}
      reload={reload}
      report={report}
      icon={<RestoreOutlinedIcon fontSize="small" />}
      label="Reopen"
      event="activate"
      title={`Reopen ${titleOf(schema)}`}
      explanation={"Each version returns to the state it had before the schema was retired. Active "
        + "versions accept new submissions again, and drafts stay drafts."}
      done={`${titleOf(schema)} is open again`}
    />
  );
}

export default SchemaReopenAction;
