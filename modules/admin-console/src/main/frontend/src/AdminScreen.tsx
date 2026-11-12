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

import { Box, Chip, Stack, Typography } from "@mui/material";
import { useTheme } from "@mui/material/styles";

import { chipStyle } from "@iap/frontend-commons/chipStyle";
import Panel from "@iap/frontend-commons/components/Panel";

// The color of the "Admin" chip, MUI's default error red. chipStyle adapts its text to the color
// scheme.
const ADMIN_RED = "#d32f2f";

interface AdminScreenProps {
  // The page's title, such as "Submission categories". Without one, the page is headed
  // "Administration", as the landing page is.
  title?: string;
  // What the page's subject belongs to, before the title and in regular weight, such as the schema
  // on the page of one of its versions
  titlePrefix?: string;
  // The state of the page's subject, such as its tags, right after the title
  status?: ReactNode;
  // The page's main action, such as a "New category" button, on the heading's row
  action?: ReactNode;
  // A line under the heading saying what the tool is for
  description?: ReactNode;
  // Lays the content directly on the page, not on a panel, for content with surfaces of its own such
  // as the landing page's widgets
  disablePanel?: boolean;
  children?: ReactNode;
}

// The frame of a page of the administration console: a heading above the content, which is on a
// panel. A page with a title also has an "Admin" chip in its heading.
function AdminScreen({ title, titlePrefix, status, action, description, disablePanel, children }: AdminScreenProps) {
  const theme = useTheme();
  return (
    <>
      <Stack sx={{ gap: 1, mb: 3 }}>
        <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 2 }}>
          <Stack direction="row" sx={{ alignItems: "center", flexWrap: "wrap", gap: 1.5 }}>
            <Typography variant="pageTitle">
              { titlePrefix && <><Box component="span" sx={{ fontWeight: "fontWeightRegular" }}>{titlePrefix}</Box>{" "}</> }
              {title ?? "Administration"}
            </Typography>
            {status}
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
