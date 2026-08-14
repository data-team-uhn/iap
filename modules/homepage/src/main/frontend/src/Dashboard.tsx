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

import WidgetDashboard from "@iap/frontend-commons/components/WidgetDashboard";

// The landing page, at `/`: a grid of widgets, each contributed by whichever module owns the
// information it shows, so what greets a user is a matter of what a deployment installs and
// enables rather than of code. The grid itself — the responsive columns, the titled widget frames
// and their tuning properties — is the shared WidgetDashboard from frontend-commons.
function Dashboard() {
  return <WidgetDashboard point="DashboardWidget" />;
}

export default Dashboard;
