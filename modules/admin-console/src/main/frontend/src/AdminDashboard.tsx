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

// The landing page of the administration console: a dashboard of the widgets on the
// `iap/adminDashboard/entry` point, one for each tool. A user is served only the widgets they can
// read, and sees the empty state if there are none.
function AdminDashboard() {
  return (
    <AdminScreen disablePanel>
      <WidgetDashboard
        point="AdminDashboard"
        empty={<Typography variant="placeholder">No administration tools are available.</Typography>}
      />
    </AdminScreen>
  );
}

export default AdminDashboard;
