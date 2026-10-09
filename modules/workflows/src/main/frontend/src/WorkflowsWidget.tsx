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

import { WORKFLOWS_ROOT, adminUrl } from "./workflowModel";

// The administration console widget summarizing the workflows: how many each homepage holds, and
// a link to view the full details for each homepage.
//
// One homepage is asked, and it answers for every homepage of its kind the reader may see.
function WorkflowsWidget() {
  return <WidgetStatList url={WORKFLOWS_ROOT} name="workflows" hrefFor={adminUrl} />;
}

export default WorkflowsWidget;
