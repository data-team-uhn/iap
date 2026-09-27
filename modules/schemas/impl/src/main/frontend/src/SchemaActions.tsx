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

import ActionBar from "@iap/frontend-commons/components/ActionBar";

import type { JcrNode } from "./schemaModel";

// A module adds an action on schemas by shipping an `ext:Extension` on this point.
export const SCHEMA_ACTIONS_POINT = "SchemaActions";

export interface SchemaActionProps {
  schema: JcrNode;
  reload: () => void | Promise<void>;
  // What to do once the schema itself is gone, when reloading it would find nothing
  removed?: () => void;
}

function SchemaActions(props: SchemaActionProps) {
  return <ActionBar point={SCHEMA_ACTIONS_POINT} {...props} />;
}

export default SchemaActions;
