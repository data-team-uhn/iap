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

import { usePageContext } from "@iap/frontend-commons/pageContext";

// PRETOTYPE. The current page's tools, such as its search, at the app bar's far end, before the bar's own
// controls. Registered on the `iap/appBar/entry` extension point, end section. Wide screens only: on narrower
// ones the page shows its tools itself, leaving the bar's room to its title.
function PageToolsEntry() {
  const context = usePageContext();
  return context?.tools ? <Box sx={{ display: { xs: "none", md: "block" }, ml: 2 }}>{context.tools}</Box> : null;
}

export default PageToolsEntry;
