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

import { type JcrNode, nameIfAny } from "./schemaModel";
import { useMoveMode } from "./schemaMove";
import { AddBelow } from "./SchemaNodeCreateAction";
import SchemaNodeDiscardAction from "./SchemaNodeDiscardAction";
import SchemaNodeEditAction from "./SchemaNodeEditAction";
import SchemaNodeMoveAction from "./SchemaNodeMoveAction";
import { isPart } from "./schemaVersionTreeModel";

interface SchemaNodeActionsProps {
  node: JcrNode;
  // What holds it, and everything it holds of the same kind, in order
  parent: JcrNode;
  siblings: JcrNode[];
  // What it is called in the actions' titles, such as "question"
  what: string;
}

// What may be done to a part or an answer option where it stands: correct it, add a part after it, move it, remove
// it. An option is added at the end of its question's, and moved from there. While something is moving, only moving
// is.
function SchemaNodeActions({ node, parent, siblings, what }: SchemaNodeActionsProps) {
  const { moving } = useMoveMode();
  const next = siblings.at(siblings.indexOf(node) + 1);
  return (
    <>
      { !moving && <SchemaNodeEditAction node={node} parent={parent} title={`Edit ${what}`} /> }
      { isPart(node) && <AddBelow parent={parent} next={nameIfAny(next)} /> }
      <SchemaNodeMoveAction node={node} what={what} />
      { !moving && <SchemaNodeDiscardAction node={node} what={what} /> }
    </>
  );
}

export default SchemaNodeActions;
