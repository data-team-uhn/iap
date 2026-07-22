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

import { Link } from "@mui/material";
import { Link as RouterLink } from "react-router";

import Logo from "@iap/frontend-commons/components/Logo";

// The application's identity in the app bar: the logo, linking back to the homepage. The logo
// is a self-titling wordmark, so deliberately no separate application name is displayed next to
// it. On narrow screens the wordmark shrinks.
// Registered on the `iap/appBar/entry` extension point, start section.
function Branding() {
  return (
    <Link component={RouterLink} to="/" underline="none" color="inherit">
      <Logo sx={{ display: "block", blockSize: { xs: 24, sm: 32 } }} />
    </Link>
  );
}

export default Branding;
