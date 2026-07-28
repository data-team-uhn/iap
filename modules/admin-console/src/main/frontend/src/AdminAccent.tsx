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

import { Box } from "@mui/material";
import { useLocation } from "react-router";

import { isAdminArea } from "@iap/frontend-commons/areas";

// A slim secondary-colour line marking the administration area, registered on the
// `iap/coreUI/frameTop` extension point right below the application bar - pinned with the frame,
// so it doesn't scroll away with the page like the admin canvas does. It renders nothing outside
// the /admin routes, and its extension node lives under the access-restricted /Extensions/Admin
// folder, so users who cannot administer anything never load it at all.
function AdminAccent() {
  const { pathname } = useLocation();

  return isAdminArea(pathname) ? <Box sx={{ height: "3px", bgcolor: "secondary.main" }} /> : null;
}

export default AdminAccent;
