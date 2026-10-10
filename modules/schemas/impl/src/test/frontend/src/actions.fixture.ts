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
import SchemaNewVersionAction from "@iap/schemas/SchemaNewVersionAction";
import SchemaRenameAction from "@iap/schemas/SchemaRenameAction";
import SchemaReopenAction from "@iap/schemas/SchemaReopenAction";
import SchemaRetireAction from "@iap/schemas/SchemaRetireAction";
import SchemaVersionActivateAction from "@iap/schemas/SchemaVersionActivateAction";
import SchemaVersionCompareAction from "@iap/schemas/SchemaVersionCompareAction";
import SchemaVersionDetailsAction from "@iap/schemas/SchemaVersionDetailsAction";
import SchemaVersionDiscardAction from "@iap/schemas/SchemaVersionDiscardAction";
import SchemaVersionEditAction from "@iap/schemas/SchemaVersionEditAction";
import SchemaVersionNewVersionAction from "@iap/schemas/SchemaVersionNewVersionAction";
import SchemaVersionRetireAction from "@iap/schemas/SchemaVersionRetireAction";

// The version actions this module contributes, in their extension order, each with the asset its extension renders
// and the places it names, if any, as the actions manager would resolve them from the repository. A test holds this
// to the extensions themselves.
export const VERSION_ACTIONS: { asset: string; component: unknown; places?: string[] }[] = [
  { asset: "SchemaVersionEditAction", component: SchemaVersionEditAction, places: [ "versionList" ] },
  { asset: "SchemaVersionDetailsAction", component: SchemaVersionDetailsAction, places: [ "versionPage" ] },
  { asset: "SchemaVersionCompareAction", component: SchemaVersionCompareAction },
  { asset: "SchemaVersionNewVersionAction", component: SchemaVersionNewVersionAction },
  { asset: "SchemaVersionActivateAction", component: SchemaVersionActivateAction },
  { asset: "SchemaVersionRetireAction", component: SchemaVersionRetireAction },
  { asset: "SchemaVersionDiscardAction", component: SchemaVersionDiscardAction },
];

export const BUILTIN_ACTIONS = VERSION_ACTIONS.map(action => action.component) as ActionComponent[];

export const SCHEMA_ACTIONS = [
  SchemaNewVersionAction, SchemaRenameAction, SchemaRetireAction, SchemaReopenAction, SchemaDiscardAction,
] as unknown as ActionComponent[];

// What the actions manager would resolve for each point this module contributes to, in the given place
export const actionsFor = (point: string, place?: string): ActionComponent[] => (point === "SchemaActions"
  ? SCHEMA_ACTIONS
  : VERSION_ACTIONS.filter(action => !action.places || (place !== undefined && action.places.includes(place)))
    .map(action => action.component) as ActionComponent[]);
