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

// Where to navigate after a successful login: the `resource` query parameter that the
// authentication handler appended when it redirected here, restricted to same-origin
// relative paths so that a crafted link cannot bounce a freshly logged-in user to
// another site. Anything else falls back to the current page, or to the homepage when
// the current page is the login page itself.
export function loginRedirectPath(location: Pick<Location, "pathname" | "search"> = window.location): string {
  const fallback = location.pathname.startsWith("/login") ? "/" : location.pathname;
  const resource = new URLSearchParams(location.search).get("resource") ?? "";

  // Only allow relative, same-origin paths starting with a single "/". Backslashes are
  // rejected too, since browsers treat them as "/" when parsing URLs, which would turn
  // "/\evil.com" into a protocol-relative external URL.
  const isValidRelativePath =
    resource.startsWith("/") &&
    !resource.startsWith("//") &&
    !resource.includes("://") &&
    !resource.includes("\\");
  if (!isValidRelativePath) {
    return fallback;
  }

  // String checks alone can be defeated by characters the URL parser removes — e.g. tabs
  // and newlines, turning "/\t/evil.example" into "//evil.example" — so additionally parse
  // the value exactly the way the browser will and require the result to stay on this
  // origin, navigating to the parsed (canonical) form rather than the raw input.
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
