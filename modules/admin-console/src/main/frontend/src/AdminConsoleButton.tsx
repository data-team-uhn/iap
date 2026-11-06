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

// The application bar's button to the administration console. It is a word, not an icon: a gear
// beside the user's own controls reads as their personal settings.
//
// It checks no permission. Its extension node is under /Extensions/Admin, and a user who cannot
// read that is not served the button.
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
