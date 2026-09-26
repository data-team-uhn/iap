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

import type { ReactNode } from "react";

import { Chip, Stack, Typography } from "@mui/material";
import { useTheme } from "@mui/material/styles";

import { chipStyle } from "@iap/frontend-commons/chipStyle";
import Panel from "@iap/frontend-commons/components/Panel";

// The theme's light-scheme error red; chipStyle adapts the text to either scheme.
const ADMIN_RED = "#d32f2f";

interface AdminScreenProps {
  // The name of the administrative tool this page hosts, e.g. "Submission categories". When unset
  // (the landing page itself), the page is headed "Administration".
  title?: string;
  // An optional main action, e.g. a "New category" button, displayed beside the heading.
  action?: ReactNode;
  // An optional line under the heading saying what the tool is for.
  description?: ReactNode;
  // Lays the content directly on the page rather than on a panel, for content that brings its own
  // surfaces (e.g. the landing page's widgets).
  disablePanel?: boolean;
  // The tool's page content.
  children?: ReactNode;
}

// The shared chrome of every page of the administration console: the page heading, with an optional
// main action beside it, above the tool's content on a panel. Wayfinding is left to the shell (the
// breadcrumb extension on the pageTop extension point).
function AdminScreen({ title, action, description, disablePanel, children }: AdminScreenProps) {
  const theme = useTheme();
  return (
    <>
      <Stack sx={{ gap: 1, mb: 3 }}>
        <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 2 }}>
          <Stack direction="row" sx={{ alignItems: "center", gap: 1.5 }}>
            <Typography variant="pageTitle">{title ?? "Administration"}</Typography>
            { title && <Chip size="small" label="Admin" sx={chipStyle(theme, ADMIN_RED)} /> }
          </Stack>
          {action}
        </Stack>
        { description && <Typography variant="description">{description}</Typography> }
      </Stack>
      {disablePanel ? children : <Panel>{children}</Panel>}
    </>
  );
}

export default AdminScreen;
