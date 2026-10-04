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

import type { ActionComponent } from "@iap/frontend-commons/actionsManager";
import SchemaDiscardAction from "@iap/schemas/SchemaDiscardAction";
import SchemaRenameAction from "@iap/schemas/SchemaRenameAction";
import SchemaRetireAction from "@iap/schemas/SchemaRetireAction";
import SchemaVersionActivateAction from "@iap/schemas/SchemaVersionActivateAction";
import SchemaVersionDetailsAction from "@iap/schemas/SchemaVersionDetailsAction";
import SchemaVersionDiscardAction from "@iap/schemas/SchemaVersionDiscardAction";
import SchemaVersionRetireAction from "@iap/schemas/SchemaVersionRetireAction";

// The actions this module contributes, in their extension order, as the actions manager would
// resolve them from the repository.
export const BUILTIN_ACTIONS = [
  SchemaVersionDetailsAction, SchemaVersionActivateAction, SchemaVersionRetireAction, SchemaVersionDiscardAction,
] as unknown as ActionComponent[];

export const SCHEMA_ACTIONS = [
  SchemaRenameAction, SchemaRetireAction, SchemaDiscardAction,
] as unknown as ActionComponent[];

// What the actions manager would resolve for each point this module contributes to
export const actionsFor = (point: string): ActionComponent[] =>
  point === "SchemaActions" ? SCHEMA_ACTIONS : BUILTIN_ACTIONS;
