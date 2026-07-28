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

import { Button } from "@mui/material";
import { useNavigate } from "react-router";

// The application bar entry leading to the administration console, registered on the
// `iap/appBar/entry` extension point (end section). A worded button rather than an icon: any
// glyph generic enough for "administration" (a gear, ...) reads as personal settings to the
// users who see it next to their own controls. The component itself performs no permission
// check: its extension node lives under the access-restricted /Extensions/Admin folder, so the
// button is only ever served to users who can read it.
function AdminConsoleButton() {
  const navigate = useNavigate();

  return (
    <Button
      color="inherit"
      onClick={() => { void navigate("/admin"); }}
      size="small"
      variant="text"
    >
      Administration
    </Button>
  );
}

export default AdminConsoleButton;
