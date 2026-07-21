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

import { Divider, Link, Stack, Typography } from "@mui/material";
import { useColorScheme } from "@mui/material/styles";
import { Link as RouterLink } from "react-router";

import Logo from "@iap/frontend-commons/components/Logo";

// The application's identity in the app bar: the logo and the application name, separated by a
// light vertical divider, together linking back to the homepage. Both are read from the page
// metadata (the `logoLight`/`logoDark` and `title` meta tags), the same source the standalone
// Logo component and the document title use, so branding stays configured in one place. On
// narrow screens the name (and its divider) give their room back to the other app bar entries,
// leaving just the logo.
// Registered on the `iap/appBar/entry` extension point, start section.
function Branding() {
  const { mode, systemMode } = useColorScheme();
  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  const appName = document.querySelector<HTMLMetaElement>('meta[name="title"]')?.content;

  return (
    <Link component={RouterLink} to="/" underline="none" color="inherit">
      <Stack direction="row" alignItems="center" gap={1.5}>
        <Logo
          mode={resolvedMode === "dark" ? "Dark" : "Light"}
          disableAffiliation
          // Constrain the logo to the toolbar's height (its own styling targets full-page use);
          // the doubled & wins the specificity contest against the component's own img rules
          sx={{ "&& > img": { display: "block", blockSize: 32, inlineSize: "auto" } }}
        />
        { /* The divider is rendered by hand rather than through the Stack's divider prop, so it
             can hide together with the name on narrow screens */ }
        <Divider orientation="vertical" flexItem sx={{ display: { xs: "none", sm: "block" } }} />
        {appName && (
          <Typography
            variant="overline"
            component="span"
            color="textSecondary"
            sx={{ display: { xs: "none", sm: "inline" } }}
          >
            {appName}
          </Typography>
        )}
      </Stack>
    </Link>
  );
}

export default Branding;
