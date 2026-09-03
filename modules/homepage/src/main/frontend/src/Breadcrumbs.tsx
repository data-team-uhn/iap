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

import { getRoutes } from "@iap/frontend-commons/routes";

// A view as returned by getRoutes(): the parsed JSON of one `ext:Extension` registered on the
// `iap/coreUI/view` extension point.
type View = Record<string, unknown>;

// The strict ancestor paths of a URL, nearest-to-root first: "/admin/categories" -> ["/admin"].
// The root itself is never a crumb - "home" is reached through the logo - and neither is the
// current page, whose own title is the page heading.
const ancestorPaths = (pathname: string): string[] => {
  const segments = pathname.replace(/\/+$/, "").split("/").filter(Boolean);
  return segments.slice(0, -1).map((_, index) => "/" + segments.slice(0, index + 1).join("/"));
};

// The breadcrumb trail, registered on the `iap/coreUI/pageTop` extension point so it appears
// above the main content of every page. Each ancestor of the current URL that corresponds to a
// registered view becomes a link named after that view (`ext:name`); on a top-level
// page there are no ancestors, so nothing is rendered at all. The trail is as access-controlled
// as the views themselves: a view the user cannot read is never served, so it simply doesn't
// appear in their trail either.
function Breadcrumbs() {
  const [ views, setViews ] = useState<View[]>([]);
  const { pathname } = useLocation();

  useEffect(() => {
    getRoutes()
      .then(response => setViews((response as View[] | undefined) ?? []))
      .catch((err: unknown) => console.error("Something went wrong loading the views", err));
  }, []);

  // Match each ancestor against the views' target URLs with the router's own semantics, so a
  // parameterized view (e.g. /Submissions/:id) still names the right crumb.
  //
  // A view whose target carries a splat never names one. A splat matches any number of segments,
  // so `/Submissions/*` claims not just `/Submissions` but every path beneath it — and a submission
  // is stored in a prefix tree, so viewing one at `/Submissions/95/21/a8/<uuid>` produced four
  // ancestors, every one of them matching that single view and rendering as a crumb called
  // "Submission". The intermediate ones are not pages at all: they are the tree's buckets, and a
  // link to one leads nowhere. Where a splat view does cover a real page, that page has its own
  // exact registration — `/admin/archive` beside `/admin/archive/*` — which is what names the crumb,
  // and which the splat would otherwise mislabel with the *entry* view's name depending on the
  // order the extensions came back in.
  const crumbs = ancestorPaths(pathname)
    .map(path => {
      const view = views.find(candidate => {
        const target = candidate["ext:targetURL"] as string | undefined;
        return target && !target.includes("*") && matchPath({ path: target, end: true }, path);
      });
      return view && { path, label: (view["ext:name"] as string | undefined) ?? path };
    })
    .filter(crumb => !!crumb);

  if (crumbs.length === 0) {
    return null;
  }

  return (
    // The trail reads as a header strip: a hairline divider separates it from the content below.
    // The horizontal inset is margin, not padding, so the divider only spans the content width
    // (the shell-published content gutter keeps it aligned with the main content's edges)
    // instead of running edge to edge.
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
