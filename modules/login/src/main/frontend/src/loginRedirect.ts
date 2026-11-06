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

// Where to go after signing in. Sling's form authentication sends a visitor to the login
// page with the path they asked for in the `resource` query parameter. Only a path on this
// site is followed, so a crafted link cannot send a user who just signed in to another
// site. Otherwise it is the current page, or the homepage on the login page itself.
export function loginRedirectPath(location: Pick<Location, "pathname" | "search"> = window.location): string {
  const fallback = location.pathname.startsWith("/login") ? "/" : location.pathname;
  const resource = new URLSearchParams(location.search).get("resource") ?? "";

  // A path starts with a single "/", and holds no "://" and no backslash. Browsers read a
  // backslash as "/", so "/\evil.com" would lead to another site.
  const isValidRelativePath =
    resource.startsWith("/") &&
    !resource.startsWith("//") &&
    !resource.includes("://") &&
    !resource.includes("\\");
  if (!isValidRelativePath) {
    return fallback;
  }

  // The URL parser drops tabs and newlines, so "/\t/evil.example" passes the checks above
  // and becomes "//evil.example". The value is parsed as the browser will parse it, and
  // must stay on this site. The parsed form is what the browser is sent to.
  let parsed: URL;
  try {
    parsed = new URL(resource, window.location.origin);
  } catch {
    return fallback;
  }
  if (parsed.origin !== window.location.origin) {
    return fallback;
  }
  return parsed.pathname + parsed.search + parsed.hash;
}
