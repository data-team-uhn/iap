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

// PRETOTYPE. The names people go by, read once per person and page: comments record who wrote them by user name.
const names = new Map<string, Promise<string>>();

function nameOf(user: string): Promise<string> {
  if (user === "iap-assistant") {
    return Promise.resolve("AI assistant");
  }
  let name = names.get(user);
  if (!name) {
    name = fetch(`/system/userManager/user/${encodeURIComponent(user)}.json`)
      .then(response => (response.ok ? response.json() : {}) as Promise<{ displayName?: unknown }>)
      .then(profile => typeof profile.displayName === "string" && profile.displayName ? profile.displayName : user)
      .catch(() => user);
    names.set(user, name);
  }
  return name;
}

export function usePersonName(user: string): string {
  const [ name, setName ] = useState(user);
  useEffect(() => {
    let current = true;
    void nameOf(user).then(found => current && setName(found));
    return () => {
      current = false;
    };
  }, [ user ]);
  return name;
}

export function PersonName({ user }: { user: string }) {
  return usePersonName(user);
}
