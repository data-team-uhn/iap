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

import { useEffect, useState } from "react";

import { Chip, CircularProgress, Link as MuiLink, List, ListItem, Stack, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { adminUrl, loadWorkflowCounts, type WorkflowHomepageCount } from "./workflowModel";

// What a homepage's chip says: how many workflows are in it, as a lower bound when the server stopped
// short of counting them all, and as a question where the count could not be read at all — that the
// homepage exists is the other half of what this widget answers, and is known either way.
function countLabel(homepage: WorkflowHomepageCount): string {
  if (homepage.count == undefined) {
    return "?";
  }
  return homepage.atLeast ? `${homepage.count}+` : `${homepage.count}`;
}

// The administration console widget summarizing the workflows: how many each homepage holds, and
// nothing more. The workflows themselves are a grid's worth of screen, which a dashboard frame does
// not have, so the widget answers the question a dashboard is for — is there anything here, and how
// much — with each homepage's name leading to its own listing, and the frame's "Manage workflows"
// action (see the extension node) leading to the one every deployment has.
// There is deliberately no error state: neither half of the load can fail outright. Discovery falls
// back to the homepage everybody has, and the counts are settled one at a time, so a homepage that
// could not be counted arrives without a number rather than taking the whole widget down with it.
function WorkflowsWidget() {
  const [ counts, setCounts ] = useState<WorkflowHomepageCount[]>();
  const fetchUtil = useAuthenticatedFetch();

  useEffect(() => {
    let cancelled = false;
    void loadWorkflowCounts(fetchUtil).then(loaded => {
      if (!cancelled) {
        setCounts(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [fetchUtil]);

  if (!counts) {
    return <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} />;
  }
  if (counts.length === 0) {
    return <Typography color="textSecondary" variant="body2">No workflows are defined yet.</Typography>;
  }

  return (
    <List dense disablePadding>
      {
        counts.map(homepage => (
          <ListItem key={homepage.path} disableGutters>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              {/* A homepage whose count could not be read is still named, with its number left open */}
              <Chip
                size="small"
                color="primary"
                label={countLabel(homepage)}
                title={homepage.count == undefined ? "The workflows here could not be counted" : undefined}
              />
              {/* Each homepage is its own page of the console, so its name is the way to the one
                  being counted; the frame's "Manage workflows" action leads to the homepage every
                  deployment has, which is where somebody with no particular one in mind is going. */}
              <MuiLink
                component={RouterLink}
                to={adminUrl(homepage.path)}
                variant="body2"
                underline="hover"
              >
                {homepage.title}
              </MuiLink>
            </Stack>
          </ListItem>
        ))
      }
    </List>
  );
}

export default WorkflowsWidget;
