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

import { Breadcrumbs as MuiBreadcrumbs, Link as MuiLink } from "@mui/material";
import { matchPath, Link as RouterLink, useLocation } from "react-router";

import { useAddedCrumbs } from "@iap/frontend-commons/pageCrumbs";
import { getRoutes } from "@iap/frontend-commons/routes";

// An extension on the `iap/coreUI/view` point, as getRoutes() gives it
type View = Record<string, unknown>;

// The paths of a URL's ancestors, from the top down, without the root and the URL itself:
// "/admin/schemas/study" gives ["/admin", "/admin/schemas"]
const ancestorPaths = (pathname: string): string[] => {
  const segments = pathname.replace(/\/+$/, "").split("/").filter(Boolean);
  return segments.slice(0, -1).map((_, index) => "/" + segments.slice(0, index + 1).join("/"));
};

// The breadcrumb trail: a link to each ancestor of the current URL that a view's `ext:targetURL`
// matches. A crumb is named by the view's `ext:name`, or by the path if it has none. The crumbs
// the page adds with usePageCrumbs follow. A user is not served the views they cannot read, and
// so gets no crumb for them. Nothing is rendered when there is no crumb.
function Breadcrumbs() {
  const [ views, setViews ] = useState<View[]>([]);
  const { pathname } = useLocation();
  const added = useAddedCrumbs();

  useEffect(() => {
    getRoutes()
      .then(response => setViews((response as View[] | undefined) ?? []))
      .catch((err: unknown) => console.error("Something went wrong loading the views", err));
  }, []);

  // The router matches each ancestor, so a target with a parameter, such as `:id`, names a crumb
  // too.
  //
  // A target with a splat names none. /Submissions/* matches every path under /Submissions. A
  // submission filed in a prefix tree would get a "Submission" crumb for each folder above it.
  const crumbs = ancestorPaths(pathname)
    .map(path => {
      const view = views.find(candidate => {
        const target = candidate["ext:targetURL"] as string | undefined;
        return target && !target.includes("*") && matchPath({ path: target, end: true }, path);
      });
      return view && { path, label: (view["ext:name"] as string | undefined) ?? path };
    })
    .filter(crumb => !!crumb)
    .concat(added);

  if (crumbs.length === 0) {
    return null;
  }

  return (
    // The inset is a margin, not a padding, so that the divider under the trail spans only the
    // width of the content
    <MuiBreadcrumbs
      separator="/"
      sx={{ mx: "var(--iap-content-gutter)", pt: 2, pb: 1, borderBottom: 1, borderColor: "divider" }}
    >
      { crumbs.map(crumb => (
        <MuiLink
          key={crumb.path}
          component={RouterLink}
          to={crumb.path}
          variant="overline"
          underline="hover"
        >
          {crumb.label}
        </MuiLink>
      ))}
    </MuiBreadcrumbs>
  );
}

export default Breadcrumbs;
