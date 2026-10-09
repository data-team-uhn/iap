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

import WidgetStatList from "@iap/frontend-commons/components/WidgetStatList";

import { SCHEMAS_ROOT } from "./schemaModel";

// The administration console widget summarizing the schemas: how many versions are active or in draft, and how
// many schemas are retired. The way through to the full tool is the frame's own "Manage" action, from the
// extension node.
function SchemasWidget() {
  return <WidgetStatList url={SCHEMAS_ROOT} name="schemas" />;
}

export default SchemasWidget;
