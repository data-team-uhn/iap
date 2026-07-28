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

// The application's "areas": URL regions that parts of the UI adapt to. Centralized here - the
// bottom layer both the shell and the feature modules depend on - so every consumer (the admin
// canvas and accent line, the breadcrumb strip, ...) agrees on the same boundaries.

// Whether the given pathname is inside the administration area, the part of the application
// carrying more responsibility: the admin console itself and every tool under it.
export const isAdminArea = (pathname: string): boolean =>
  pathname === "/admin" || pathname.startsWith("/admin/");

// The background token painting the current area's canvas, applied once by the page shell to the
// whole scrolling content region; undefined keeps the plain page background. Painting the canvas
// in the shell lets everything displayed on top (the breadcrumb strip, the admin screens, ...)
// simply stay transparent instead of each re-checking the area.
export const areaBackground = (pathname: string): string | undefined =>
  isAdminArea(pathname) ? "background.admin" : undefined;
