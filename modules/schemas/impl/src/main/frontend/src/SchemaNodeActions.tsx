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
import { AddBelow } from "./SchemaNodeCreateAction";
import SchemaNodeDiscardAction from "./SchemaNodeDiscardAction";
import SchemaNodeEditAction from "./SchemaNodeEditAction";

interface SchemaNodeActionsProps {
  node: JcrNode;
  // What holds it
  parent: JcrNode;
  // Everything the parent holds of its kind, in order
  siblings: JcrNode[];
  // What it is called in the actions' titles, such as "question"
  what: string;
}

// What may be done to a part or an answer option where it stands: correct it, add after it, remove it.
function SchemaNodeActions({ node, parent, siblings, what }: SchemaNodeActionsProps) {
  const next = siblings.at(siblings.indexOf(node) + 1);
  return (
    <>
      <SchemaNodeEditAction node={node} title={`Edit ${what}`} />
      <AddBelow parent={parent} next={nameIfAny(next)} />
      <SchemaNodeDiscardAction node={node} what={what} />
    </>
  );
}

export default SchemaNodeActions;
