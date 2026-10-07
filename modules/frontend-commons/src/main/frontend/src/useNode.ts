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

import { useCallback, useEffect, useState } from "react";

import { useAuthenticatedFetch, type AuthenticatedFetch } from "./reLogin";
import { describeRequestFailure, messageOf, RequestError } from "./requestFailure";

// A node as the repository serializes it
type JcrNode = Record<string, unknown>;

// Reads one serialized node, with failures already worded for the person who will read them.
export async function readNode(doFetch: AuthenticatedFetch, path: string, selectors: string): Promise<JcrNode> {
  try {
    const response = await doFetch(`${path}.${selectors}.json`);
    if (!response.ok) {
      throw new RequestError(response.status);
    }
    return await response.json() as JcrNode;
  } catch (error: unknown) {
    throw new Error(describeRequestFailure(error));
  }
}

// What a page reading one node needs: the parsed value, whether the first read is still going, what
// went wrong with the last one, and a way to read again. A failed re-read keeps the last good value, so
// the page stays readable under the error.
export function useNode<T>(path: string, selectors: string, parse: (node: JcrNode) => T) {
  const doFetch = useAuthenticatedFetch();
  const [ value, setValue ] = useState<T>();
  const [ loading, setLoading ] = useState(true);
  const [ loadError, setLoadError ] = useState<string>();

  const reload = useCallback((): Promise<void> =>
    readNode(doFetch, path, selectors)
      .then(node => {
        setValue(parse(node));
        setLoadError(undefined);
      })
      .catch((error: unknown) => setLoadError(messageOf(error)))
      .finally(() => setLoading(false)), [ doFetch, path, selectors, parse ]);

  useEffect(() => {
    void reload();
  }, [ reload ]);

  return { value, loading, loadError, reload, doFetch };
}
