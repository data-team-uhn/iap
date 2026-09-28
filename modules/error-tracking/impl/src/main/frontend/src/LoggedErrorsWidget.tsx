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

import { LOGGED_ERRORS_PATH } from "./errorTrackingApi";

// The administration console widget summarizing what has been recorded: how much is asking for
// attention, and how much there is altogether. The way into the triage view is the dashboard frame's
// own header action, so this renders only the figures.
function LoggedErrorsWidget() {
  return <WidgetStatList url={LOGGED_ERRORS_PATH} name="recorded errors" />;
}

export default LoggedErrorsWidget;
