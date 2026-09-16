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

import { Alert, CircularProgress } from "@mui/material";
import { Navigate, useLocation } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import WorkflowEditor from "./WorkflowEditor";
import WorkflowManager from "./WorkflowManager";
import {
  adminUrl,
  consoleTarget,
  loadWorkflowHomepages,
  WORKFLOWS_ROOT,
  type ConsoleTarget,
} from "./workflowModel";
import WorkflowsView from "./WorkflowsView";

// Everything the console shows below /admin/workflows, chosen by what the URL is about.
//
// One route rather than one per page, because the URL is a repository path of unknown length after a
// fixed prefix — /admin/workflows/Workflows/review/2-0 — and a route may only end in a splat. The
// only route that can match these URLs is the one that matches all of them, and something has to
// read the rest. That is the price of every prefix of a console URL being a page in its own right:
// /admin/workflows/Workflows/review is the workflow, and dropping another segment is the homepage it
// is stored in.
//
// The path is therefore the thing being looked at and nothing else; the one page that opens on top of
// it is asked for by a suffix (/admin/workflows/Workflows/review/2-0.edit), so a version and its
// editor are the same page asked two ways rather than one being read as a page below the other.
//
// Which of the three a path is takes the list of homepages, since a homepage sits at no predictable
// depth (see consoleTarget). That list is discovered once and kept for the session, so this costs a
// request when the console is first opened and nothing on any navigation after it.
//
// The console's root is the exception: it addresses nothing, and redirects to the default homepage's
// listing so that a URL a user is likely to type or trim to lands on a page.
function WorkflowConsole() {
  const location = useLocation();
  const fetchUtil = useAuthenticatedFetch();
  const [ homepages, setHomepages ] = useState<string[]>();

  useEffect(() => {
    let cancelled = false;
    void loadWorkflowHomepages(fetchUtil).then(discovered => {
      if (!cancelled) {
        setHomepages(discovered.map(homepage => homepage.path));
      }
    });
    return () => {
      cancelled = true;
    };
  }, [fetchUtil]);

  // The root is the one URL the homepages have no say in, so it is answered straight away rather
  // than behind a spinner the redirect would throw away. It shows the homepage every admin user has
  // access to, since a listing belongs to a homepage and the root is not a page of its own.
  const target: ConsoleTarget = consoleTarget(location.pathname, homepages ?? []);
  if (target.kind === "root") {
    return <Navigate to={adminUrl(WORKFLOWS_ROOT)} replace />;
  }

  if (homepages === undefined) {
    return (
      <AdminScreen title="Workflows">
        <CircularProgress
          aria-label="Loading the workflows"
          size={24}
          sx={{ display: "block", mx: "auto", my: 2 }}
        />
      </AdminScreen>
    );
  }

  switch (target.kind) {
    case "homepage":
      // The list, opened on the tab this URL names: a homepage is a page of its own, being what the
      // workflow above it is stored in
      return <WorkflowsView homepage={target.path} />;
    case "workflow":
      return <WorkflowManager path={target.path} />;
    case "version":
      return <WorkflowEditor path={target.path} editing={target.editing} />;
    case "unknown":
      // A URL that names nothing showable says so plainly, rather than rendering the empty workflow that
      // querying the repository for it would produce.
      return (
        <AdminScreen title="Workflows">
          <Alert severity="warning">
            {location.pathname} does not name a workflow, a version, or a page of one.
          </Alert>
        </AdminScreen>
      );
  }
}

export default WorkflowConsole;
