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

import { Box, Stack, Typography } from "@mui/material";
import { useLocation } from "react-router";

interface AdminScreenProps {
  // The name of the administrative tool this page hosts, e.g. "Submission categories". When unset
  // (the landing page itself), the page is headed "Administration".
  title?: string;
  // An optional main action displayed next to the heading, e.g. a "New category" button.
  action?: ReactNode;
  // The tool's page content.
  children?: ReactNode;
}

// The shared chrome wrapping every page of the administration console: the page heading and an
// optional main action, above the tool's content. Deliberately borderless and breadcrumb-free:
// the page sits directly on the background, and wayfinding is left to the shell (e.g. a future
// breadcrumb extension on the pageTop extension point).
function AdminScreen({ title, action, children }: AdminScreenProps) {
  // On a nested page (deeper than one level) the shell's breadcrumb trail renders right above
  // the content, already carrying its own spacing, so the screen's top margin would double up.
  // Top-level pages (like the console's landing page) have no trail and keep the margin.
  const { pathname } = useLocation();
  const nested = pathname.replace(/\/+$/, "").split("/").filter(Boolean).length > 1;

  // The screen itself is transparent: the administration area's tinted canvas is painted once by
  // the page shell (see areaBackground in frontend-commons), and the pinned accent line under the
  // application bar comes from the AdminAccent frameTop extension.
  return (
    <Box sx={{ mt: nested ? 0 : 2 }}>
      <Stack direction="row" sx={{ justifyContent: "space-between", alignItems: "center", gap: 2 }}>
        <Typography variant="pageTitle">
          {title ?? "Administration"}
        </Typography>
        {action}
      </Stack>
      <Box sx={{ mt: 3 }}>
        {children}
      </Box>
    </Box>
  );
}

export default AdminScreen;
