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

import { type ReactNode, useState } from "react";

import ConditionDialog from "@iap/conditions/ConditionDialog";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import { offers } from "@iap/frontend-commons/workflowEvents";

import { pathOf } from "./schemaModel";
import { useMoveMode } from "./schemaMove";
import { useTreeEvent } from "./schemaTree";
import { conditionOf } from "./schemaVersionTreeModel";

export interface ConditionEditor {
  // Opens the dialog that sets the condition. Absent when the part's condition cannot be set. Also absent while a
  // part of the tree is being moved, until the move is done or cancelled.
  edit?: () => void;
  // The dialog, while it is open
  dialog: ReactNode;
}

// Sets when a part applies. What its condition can compare is the server's to say, for this part, and the condition
// is written whole in one event. A card opens the dialog from its actions, and from the line saying when the part
// applies.
export function useConditionEditor(node: SerializedNode, what: string): ConditionEditor {
  const [ editing, setEditing ] = useState(false);
  const { moving } = useMoveMode();
  const send = useTreeEvent();
  if (!offers(node, "condition")) {
    return { dialog: null };
  }
  return {
    edit: moving ? undefined : () => setEditing(true),
    dialog: editing && (
      <ConditionDialog
        title={`When this ${what} applies`}
        condition={conditionOf(node)}
        path={pathOf(node)}
        onClose={() => setEditing(false)}
        onSave={content => send(node, "condition", { content: JSON.stringify(content) })}
      />
    ),
  };
}
