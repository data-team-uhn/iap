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

import { StrictMode, useEffect, useState, type ComponentType } from "react";

import { CssBaseline } from "@mui/material";
import { StyledEngineProvider, ThemeProvider } from "@mui/material/styles";
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, Route, RouterProvider, Routes } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import { getRoutes } from "@iap/frontend-commons/routes";
import { ReLoginProvider } from "@iap/login/ReLoginDialog";

import PageLayout from "./PageLayout";

// An extension on the `iap/coreUI/view` point, with its `asset:` properties loaded. Its
// `ext:targetURL` is the path it shows, as a router pattern, and its `ext:render` the
// component that shows it.
type View = Record<string, unknown>;

// The main content area: the view whose `ext:targetURL` matches the current URL. Nothing
// is shown when none matches.
function Main() {
  const [ views, setViews ] = useState<View[]>([]);

  useEffect(() => {
    getRoutes()
      .then(response => setViews(response as View[]))
      .catch((err: unknown) => console.error("Something went wrong loading the views", err));
  }, []);

  return (
    <Routes>
      {
        views.map((view, index) => {
          const ViewComponent = view["ext:render"] as ComponentType<{ extension: View }>;
          return (
            <Route
              path={view["ext:targetURL"] as string}
              element={<ViewComponent extension={view} />}
              key={"view-" + index}
            />
          );
        })
      }
    </Routes>
  );
}

// The page shell is inside the router, so that its extensions can use the router's links and
// location hooks.
const router = createBrowserRouter([
  {
    path: "*",
    element: <PageLayout><Main /></PageLayout>,
  },
]);

// Mounts the application into the page's `#main-container`. On a page without one, as in a
// test, the module only exports Main.
const container = document.querySelector('#main-container');
if (container) {
  createRoot(container).render(
    <StrictMode>
      <StyledEngineProvider injectFirst>
        <ThemeProvider theme={appTheme} defaultMode="system">
          <CssBaseline enableColorScheme />
          <ReLoginProvider>
            <NoticeProvider>
              <RouterProvider router={router} />
            </NoticeProvider>
          </ReLoginProvider>
        </ThemeProvider>
      </StyledEngineProvider>
    </StrictMode>
  );
}

export default Main;
