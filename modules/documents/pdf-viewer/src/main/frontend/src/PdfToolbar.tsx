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
// The row of controls above the document. The caller supplies the controls; this places them.
import type { ReactNode } from "react";

import { Box } from "@mui/material";

// The toolbar's one row: `start` at its start, then the search and the `tools` at its end. It stays on one
// line, even on a phone.
export default function PdfToolbar({ start, search, tools }: {
  start?: ReactNode;
  // The search field, or a button opening it.
  search: ReactNode;
  tools?: ReactNode;
}) {
  return (
    <Box sx={{ display: "flex", alignItems: "center" }}>
      {start}
      <Box sx={{ flexGrow: 1 }} />
      {search}
      {tools}
    </Box>
  );
}
