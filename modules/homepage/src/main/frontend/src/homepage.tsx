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
import { getRoutes } from "@iap/frontend-commons/routes";

import PageLayout from "./PageLayout";

// A view is the parsed JSON of one `iap:Extension` registered on the `iap/coreUI/view`
// extension point, with its `asset:` properties already resolved. A view has an
// `iap:targetURL` (the URL it is responsible for) and an `iap:extensionRender` (the
// React component that displays it).
type View = Record<string, unknown>;

// The main content area: renders the registered view whose `iap:targetURL` matches the
// current browser URL. Views are contributed by other modules through the
// `iap/coreUI/view` extension point and retrieved via getRoutes().
function Main() {
  const [ views, setViews ] = useState<View[]>([]);

  useEffect(() => {
    getRoutes()
      .then(response => setViews((response as View[] | undefined) ?? []))
      .catch((err: unknown) => console.error("Something went wrong loading the views", err));
  }, []);

  return (
    <Routes>
      {
        views.map((view, index) => {
          const ViewComponent = view["iap:extensionRender"] as ComponentType<{ extension: View }>;
          return (
            <Route
              path={view["iap:targetURL"] as string}
              element={<ViewComponent extension={view} />}
              key={"view-" + index}
            />
          );
        })
      }
    </Routes>
  );
}

// The page shell wraps the routed view from inside the router, so that shell extensions
// (navigation entries, toolbars, ...) can use react-router links and location hooks.
const router = createBrowserRouter([
  {
    path: "*",
    element: <PageLayout><Main /></PageLayout>,
  },
]);

// When loaded as the homepage entry script, mount the router; when imported (e.g. from
// tests), only export the Main component.
const container = document.querySelector('#main-container');
if (container) {
  createRoot(container).render(
    <StrictMode>
      <StyledEngineProvider injectFirst>
        <ThemeProvider theme={appTheme} defaultMode="system">
          <CssBaseline enableColorScheme />
          <RouterProvider router={router} />
        </ThemeProvider>
      </StyledEngineProvider>
    </StrictMode>
  );
}

export default Main;
