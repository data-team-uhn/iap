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

import { Box, Stack, Tooltip, Typography } from "@mui/material";
import { visuallyHidden } from "@mui/utils";

import { usePageContext } from "@iap/frontend-commons/pageContext";

// PRETOTYPE. The current page's title and what goes with it, in the app bar after the logo, so it stays in view.
// Registered on the `iap/appBar/entry` extension point, start section. Shows nothing on pages that publish
// nothing. A long title is cut short, whole on hover.
function PageContextEntry() {
  const context = usePageContext();
  if (!context) {
    return null;
  }
  return (
    <>
      {/* A phone's bar has room for the short form only: the heading stays for those who listen */}
      <Typography component="h1" sx={{ ...visuallyHidden, display: { sm: "none" } }}>{context.title}</Typography>
      {context.short && (
        <Typography
          variant="subtitle2" aria-hidden
          sx={{ display: { sm: "none" }, ml: 1, fontWeight: "fontWeightBold", whiteSpace: "nowrap",
            color: "primary.main" }}
        >
          {context.short}
        </Typography>
      )}
      <Stack direction="row" sx={{ flex: "0 1 auto", minWidth: 0, alignItems: "baseline", gap: 2, ml: 2,
        display: { xs: "none", sm: "flex" } }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" sx={{ alignItems: "center", gap: 1, minWidth: 0 }}>
            <Tooltip title={context.title}>
              <Typography
                variant="subtitle1" component="h1"
                sx={{ fontWeight: "fontWeightBold", minWidth: 0, overflow: "hidden", textOverflow: "ellipsis",
                  whiteSpace: "nowrap", color: "primary.main" }}
              >
                {context.title}
              </Typography>
            </Tooltip>
            <Box sx={{ display: { xs: "none", md: "flex" }, gap: 0.5, flexShrink: 0 }}>{context.badges}</Box>
          </Stack>
          <Typography
            variant="caption"
            sx={{ display: "block", color: "text.secondary", overflow: "hidden", textOverflow: "ellipsis",
              whiteSpace: "nowrap" }}
          >
            {context.details}
          </Typography>
        </Box>
        {context.end && <Box sx={{ flexShrink: 0 }}>{context.end}</Box>}
      </Stack>
    </>
  );
}

export default PageContextEntry;
