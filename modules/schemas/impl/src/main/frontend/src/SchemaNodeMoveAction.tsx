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

import OpenWithIcon from "@mui/icons-material/OpenWith";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import { type JcrNode } from "./schemaModel";
import { useMoveMode } from "./schemaMove";

// Starts moving a part or an answer option of a draft, or, while it is the one moving, stops.
function SchemaNodeMoveAction({ node, what }: { node: JcrNode; what: string }) {
  const { isMoving, start, cancel } = useMoveMode();
  if (!offers(node, "move")) {
    return null;
  }
  const pressed = isMoving(node);
  return (
    <ActionIcon
      label="Move"
      icon={<OpenWithIcon fontSize="small" />}
      pressed={pressed}
      onClick={event => (pressed ? cancel() : start({ node, what, trigger: event.currentTarget }))}
    />
  );
}

export default SchemaNodeMoveAction;
