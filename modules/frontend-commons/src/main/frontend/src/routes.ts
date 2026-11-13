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

import { loadExtensions } from '@iap/ui-extension/extensionManager';

let routes: unknown[] | null = null;
let routesRequest: Promise<unknown[]> | null = null;

// The registered views: the extensions of the `Views` point, each of which shows the page for a URL
// in the main content area. A view has the path it shows (`ext:targetURL`) and the component that
// shows it (`ext:render`). It may have a name (`ext:name`) and a description (`ext:hint`). The views
// are kept for the page load once they are read, even when there are none. A failed read is not kept,
// and the next call reads them again.
//
// @return the views; rejects if the `Views` point cannot be read
const getRoutes = async function(): Promise<unknown[]> {
  if (routes) {
    return routes;
  }
  routesRequest ??= loadExtensions("Views")
    .then(extensions => routes = extensions)
    .finally(() => routesRequest = null);
  return routesRequest;
};

export { getRoutes };
