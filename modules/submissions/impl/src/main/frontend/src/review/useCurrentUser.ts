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

import { SESSION_INFO_URL, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

// PRETOTYPE. Who is signed in, once known.
export function useCurrentUser(): string | undefined {
  const doFetch = useAuthenticatedFetch();
  const [ user, setUser ] = useState<string>();
  useEffect(() => {
    void doFetch(SESSION_INFO_URL)
      .then(response => (response.ok ? response.json() : {}) as Promise<{ userID?: string }>)
      .then(info => setUser(info.userID));
  }, [ doFetch ]);
  return user;
}

// The groups someone belongs to, by principal name, once known
export function useMyGroups(me: string | undefined): string[] {
  const doFetch = useAuthenticatedFetch();
  const [ groups, setGroups ] = useState<string[]>([]);
  useEffect(() => {
    if (!me) {
      return;
    }
    void doFetch(`/system/userManager/user/${encodeURIComponent(me)}.json`)
      .then(response => (response.ok ? response.json() : {}) as Promise<{ memberOf?: string[] }>)
      .then(user => setGroups((user.memberOf ?? []).map(group => group.split("/").at(-1) ?? group)));
  }, [ doFetch, me ]);
  return groups;
}
