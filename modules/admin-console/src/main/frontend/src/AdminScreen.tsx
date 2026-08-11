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
  // An optional main action, e.g. a "New category" button, displayed at the top of the working
  // panel - inside the marked administrative zone, where an action on administrative data belongs.
  action?: ReactNode;
  // The tool's page content.
  children?: ReactNode;
}

// The shared chrome wrapping every page of the administration console: the page heading and an
// optional main action, above the tool's content. Deliberately borderless and breadcrumb-free:
// the page sits directly on the background, and wayfinding is left to the shell (the breadcrumb
// extension on the pageTop extension point).
function AdminScreen({ title, action, children }: AdminScreenProps) {
  // On a nested page (deeper than one level) the shell's breadcrumb trail renders right above
  // the content; the working panel pulls itself up over the main region's top gutter (published
  // by the shell as --iap-content-gutter) plus its own 2px border, so the red border lands
  // exactly on the trail's divider line, visually attaching the trail to the zone. Top-level
  // pages (like the console's landing page) have no trail and keep a normal top margin instead.
  const { pathname } = useLocation();
  const nested = pathname.replace(/\/+$/, "").split("/").filter(Boolean).length > 1;
  // Longhand marginTop for the calc branch: it must reach the stylesheet verbatim, without
  // going through the spacing shorthand's value transformer (which mangles it).
  const collapseOntoTrail = nested
    ? { marginTop: "calc(-1 * var(--iap-content-gutter) - 2px)" }
    : { mt: 2 };

  // The admin widget container carries the whole "more responsibility here" signal: a
  // red border hugging a muted primary tint, on an otherwise plain page. Keeping both on the
  // panel (rather than on the page shell) means the frame visibly belongs to the content it
  // encloses and naturally scrolls with it, and the tint gives the tool's surfaces (category
  // cards, widgets) something to stand out against.
  //
  // A tool's title and main action live inside the panel, beside each other: the action acts on
  // administrative data, so it belongs in the zone, and the breadcrumb trail above already
  // provides the outside-the-zone wayfinding. The console's landing page instead keeps its
  // "Administration" heading outside, introducing the zone as a whole.
  const heading = <Typography variant="pageTitle">{title ?? "Administration"}</Typography>;

  return (
    <Box sx={collapseOntoTrail}>
      { !title && heading }
      <Box
        sx={{
          mt: title ? 0 : 3,
          p: 3,
          border: 2,
          borderColor: "admin.main",
          borderRadius: 2,
          bgcolor: "background.admin",
        }}
      >
        { (title !== undefined || action !== undefined)
          && (
            <Stack
              direction="row"
              sx={{
                justifyContent: title ? "space-between" : "flex-end",
                alignItems: "center",
                gap: 2,
                mb: 3,
              }}
            >
              { title && heading }
              {action}
            </Stack>
          )}
        {children}
      </Box>
    </Box>
  );
}

export default AdminScreen;
