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

// PRETOTYPE. When a reviewer last looked at a discussion, kept in their own browser: a real version would
// record it on the server, so that it follows them from one device to the next.

function key(me: string, path: string): string {
  return `iap.review.seen.${me}.${path}`;
}

export function seenAt(me: string, path: string): string | undefined {
  try {
    return localStorage.getItem(key(me, path)) ?? undefined;
  } catch {
    return undefined;
  }
}

export function markSeen(me: string, path: string): string {
  const now = new Date().toISOString();
  try {
    localStorage.setItem(key(me, path), now);
  } catch {
    // Unread counts just stay as they were
  }
  return now;
}
