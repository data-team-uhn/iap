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

import { type ReactNode } from "react";

import { Paper, Stack, Typography } from "@mui/material";

interface PanelProps {
  // Optional heading of the panel.
  title?: ReactNode;
  // Optional explanatory line under the title.
  subtitle?: ReactNode;
  // Optional control displayed in line with the title, e.g. a button acting on the panel's content.
  action?: ReactNode;
  // The panel's content.
  children?: ReactNode;
}

// A block of a page's content on a paper surface, standing out against the page canvas the same
// way dashboard widgets do. Pages are built as a stack of panels rather than of loose sections.
function Panel({ title, subtitle, action, children }: PanelProps) {
  const header = Boolean(title) || Boolean(subtitle) || Boolean(action);
  return (
    <Paper sx={{ p: 2, border: "none" }}>
      { header && (
        <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "flex-start", gap: 1, mb: 2 }}>
          <Stack sx={{ gap: 0.5 }}>
            { title && <Typography variant="h6" component="h2">{title}</Typography> }
            { subtitle && <Typography variant="description">{subtitle}</Typography> }
          </Stack>
          {action}
        </Stack>
      ) }
      {children}
    </Paper>
  );
}

export default Panel;
