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

import EditOutlinedIcon from "@mui/icons-material/EditOutlined";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import FieldsDialog from "@iap/frontend-commons/fields/FieldsDialog";
import { fieldsOf } from "@iap/frontend-commons/fields/fieldsModel";
import { patch } from "@iap/frontend-commons/workflowEvents";

import { type JcrNode, offers } from "./schemaModel";
import { useTreeEvent } from "./schemaTree";

// Corrects what a part or an option says, when the server offers it: the fields are the ones its update
// would change.
function SchemaNodeEditAction({ node, title }: { node: JcrNode; title: string }) {
  const [ editing, setEditing ] = useState(false);
  const send = useTreeEvent();
  if (!offers(node, "update") || fieldsOf(node).length === 0) {
    return null;
  }
  return (
    <>
      <ActionIcon label="Edit" icon={<EditOutlinedIcon fontSize="small" />} onClick={() => setEditing(true)} />
      { editing && (
        <FieldsDialog
          title={title}
          node={node}
          onSave={changes => send(node, "update", patch(changes))}
          onClose={() => setEditing(false)}
        />
      ) }
    </>
  );
}

export default SchemaNodeEditAction;
