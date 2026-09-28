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

import { useContext } from "react";

import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";

import { EventAction } from "@iap/frontend-commons/components/EventAction";

import { type JcrNode, offers, pathOf } from "./schemaModel";
import { ReloadTree } from "./schemaTree";

// Removes a part or an answer option from a draft, into the archive. What a condition elsewhere depends on
// stays, and the refusal says whose conditions those are.
function SchemaNodeDiscardAction({ node, what }: { node: JcrNode; what: string }) {
  const reload = useContext(ReloadTree);
  if (!offers(node, "discard")) {
    return null;
  }
  return (
    <EventAction
      path={pathOf(node)}
      reload={reload}
      icon={<DeleteOutlinedIcon fontSize="small" />}
      label="Remove"
      event="discard"
      color="error"
      title={`Remove this ${what}`}
      explanation={`The ${what} goes to the archive, with everything in it.`}
    />
  );
}

export default SchemaNodeDiscardAction;
