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

import AltRouteOutlinedIcon from "@mui/icons-material/AltRouteOutlined";

import { ActionsMenu } from "@iap/frontend-commons/components/ActionsMenu";
import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import { usePhone } from "@iap/frontend-commons/usePhone";

import { nameIfAny } from "./schemaModel";
import { useMoveMode } from "./schemaMove";
import { AddBelow } from "./SchemaNodeCreateAction";
import SchemaNodeDiscardAction from "./SchemaNodeDiscardAction";
import SchemaNodeEditAction from "./SchemaNodeEditAction";
import SchemaNodeMoveAction from "./SchemaNodeMoveAction";
import SchemaOptionStepActions from "./SchemaOptionStepActions";
import { headingOf, isPart, shownNameOf } from "./schemaVersionTreeModel";

interface SchemaNodeActionsProps {
  node: SerializedNode;
  // What holds it, and everything it holds of the same kind, in order
  parent: SerializedNode;
  siblings: SerializedNode[];
  // What it is called in the actions' titles, such as "question"
  what: string;
  // Sets when a part applies, where that can be set
  editCondition?: () => void;
}

// What may be done to a part or an answer option where it stands: correct it, set when a part applies, add a part
// after it, move it, remove it. An option is added at the end of its question's, and moved up or down from there.
// While a part is moving, only moving is. On a phone, all but moving one step are in a menu.
function SchemaNodeActions({ node, parent, siblings, what, editCondition }: SchemaNodeActionsProps) {
  const { moving } = useMoveMode();
  const phone = usePhone();
  const next = siblings.at(siblings.indexOf(node) + 1);
  const label = `Actions for “${shownNameOf(node)}”`;
  const edit = !moving && <SchemaNodeEditAction node={node} parent={parent} title={`Edit ${what}`} />;
  const discard = !moving && <SchemaNodeDiscardAction node={node} what={what} />;
  if (!isPart(node)) {
    const steps = !moving && <SchemaOptionStepActions option={node} question={parent} options={siblings} />;
    return phone
      ? <>{steps}<ActionsMenu label={label}>{edit}{discard}</ActionsMenu></>
      : <>{edit}{steps}{discard}</>;
  }
  return (
    <ActionsMenu label={label} inline={moving !== undefined}>
      {edit}
      { editCondition && (
        <ActionIcon label="When it applies" onClick={editCondition}
          icon={<AltRouteOutlinedIcon fontSize="small" sx={{ color: "condition.main" }} />} />
      ) }
      <AddBelow parent={parent} after={headingOf(node)} next={nameIfAny(next)} />
      <SchemaNodeMoveAction node={node} what={what} />
      {discard}
    </ActionsMenu>
  );
}

export default SchemaNodeActions;
