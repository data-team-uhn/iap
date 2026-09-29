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

import { useContext, useRef, useState } from "react";

import EditOutlinedIcon from "@mui/icons-material/EditOutlined";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import FieldsDialog from "@iap/frontend-commons/fields/FieldsDialog";
import { creatableOf, fieldsOf } from "@iap/frontend-commons/fields/fieldsModel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { offers, patch, sendEvent } from "@iap/frontend-commons/workflowEvents";

import { type JcrNode, lastSegmentOf, pathOf, renamedPath } from "./schemaModel";
import SchemaNodeIdentifier from "./SchemaNodeIdentifier";
import { ReloadTree, useTreeEvent } from "./schemaTree";
import { isPart } from "./schemaVersionTreeModel";

// Corrects what a part or an option says, when the server offers it: the fields are the ones its update
// would change. A part's identifier is shown too, and renamed on its own where that is offered. The tree is read
// again only once the dialog is done: the part's card holds the dialog, and would go with it, under its new name.
function SchemaNodeEditAction({ node, parent, title }: { node: JcrNode; parent: JcrNode; title: string }) {
  const [ editing, setEditing ] = useState(false);
  // Where the node is while the dialog is open, which renaming it changes
  const [ path, setPath ] = useState(pathOf(node));
  const renamed = useRef(false);
  const doFetch = useAuthenticatedFetch();
  const reload = useContext(ReloadTree);
  const send = useTreeEvent();
  if (!offers(node, "update") || fieldsOf(node).length === 0) {
    return null;
  }
  // A rename takes exactly the name asked for, where the part stands, or is refused
  const rename = async (name: string) => {
    await sendEvent(doFetch, path, "rename", { name });
    setPath(renamedPath(path, name));
    renamed.current = true;
  };
  return (
    <>
      <ActionIcon label="Edit" icon={<EditOutlinedIcon fontSize="small" />} onClick={() => {
        setPath(pathOf(node));
        setEditing(true);
      }} />
      { editing && (
        <FieldsDialog
          title={title}
          node={node}
          onSave={async changes => {
            renamed.current = false;
            await send(path, "update", patch(changes));
          }}
          onClose={() => {
            setEditing(false);
            if (renamed.current) {
              renamed.current = false;
              void reload();
            }
          }}
          afterFirstField={isPart(node) ? () => (
            <SchemaNodeIdentifier
              name={lastSegmentOf(path)}
              rename={offers(node, "rename") ? rename : undefined}
              // What names may be is the create workflow's to say, which renaming keeps to as well
              hint={creatableOf(parent).find(type => type.type === node["jcr:primaryType"])?.nameHint}
            />
          ) : undefined}
        />
      ) }
    </>
  );
}

export default SchemaNodeEditAction;
