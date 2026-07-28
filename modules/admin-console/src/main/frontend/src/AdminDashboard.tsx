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

import { Typography } from "@mui/material";

import WidgetDashboard from "@iap/frontend-commons/components/WidgetDashboard";

import AdminScreen from "./AdminScreen";

// The landing page of the administration console: one widget per administrative tool, registered
// on the `iap/adminDashboard/entry` extension point. Each tool's widget shows a live summary of
// its area (e.g. the category tool lists the current top-level categories) and leads into the
// tool's own page — much more informative than a plain list of links, and reusing the exact same
// layout as the user dashboard. Access control is entirely repository-side: extensions the user
// cannot read are simply never served, so a non-administrator sees the empty state (and no way to
// reach the console in the first place).
function AdminDashboard() {
  return (
    <AdminScreen>
      <WidgetDashboard
        point="AdminDashboard"
        empty={<Typography color="textSecondary">No administration tools are available.</Typography>}
      />
    </AdminScreen>
  );
}

export default AdminDashboard;
