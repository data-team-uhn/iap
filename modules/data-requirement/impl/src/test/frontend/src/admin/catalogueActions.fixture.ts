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

import CatalogueReopenAction from "@iap/data-requirement/admin/CatalogueReopenAction";
import CatalogueRetireAction from "@iap/data-requirement/admin/CatalogueRetireAction";
import CatalogueVersionActivateAction from "@iap/data-requirement/admin/CatalogueVersionActivateAction";
import CatalogueVersionRetireAction from "@iap/data-requirement/admin/CatalogueVersionRetireAction";
import type { ActionComponent } from "@iap/frontend-commons/actionsManager";

// The actions this module contributes, by the extension point that shows them, in their extension order, as the
// actions manager would resolve them from the repository. A test checks these lists against the extensions.
export const ACTIONS: Record<string, { asset: string; component: unknown }[]> = {
  CatalogueActions: [
    { asset: "CatalogueRetireAction", component: CatalogueRetireAction },
    { asset: "CatalogueReopenAction", component: CatalogueReopenAction },
  ],
  CatalogueVersionActions: [
    { asset: "CatalogueVersionActivateAction", component: CatalogueVersionActivateAction },
    { asset: "CatalogueVersionRetireAction", component: CatalogueVersionRetireAction },
  ],
};

// What the actions manager would resolve for a point this module contributes to
export const actionsFor = (point: string): ActionComponent[] =>
  (ACTIONS[point] ?? []).map(action => action.component) as ActionComponent[];
