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

import { useState } from "react";

import DriveFileRenameOutlineIcon from "@mui/icons-material/DriveFileRenameOutline";


import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import DetailsDialog, { editableText } from "./DetailsDialog";
import { ActionIcon } from "./EventAction";
import { patch, sendEvent } from "./schemaEvents";
import { offers, pathOf } from "./schemaModel";

import type { SchemaActionProps } from "./SchemaActions";

function SchemaRenameAction({ schema, reload }: SchemaActionProps) {
  const [ editing, setEditing ] = useState(false);
  const doFetch = useAuthenticatedFetch();
  if (!offers(schema, "update") || editableText(schema).length === 0) {
    return null;
  }
  return (
    <>
      <ActionIcon label="Rename" icon={<DriveFileRenameOutlineIcon fontSize="small" />} onClick={() => setEditing(true)} />
      { editing && (
        <DetailsDialog
          title="Rename schema"
          node={schema}
          onSave={async changes => {
            await sendEvent(doFetch, pathOf(schema), "update", patch(changes));
            reload();
          }}
          onClose={() => setEditing(false)}
        />
      ) }
    </>
  );
}

export default SchemaRenameAction;
