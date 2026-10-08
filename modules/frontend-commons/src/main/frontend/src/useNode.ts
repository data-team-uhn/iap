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

import { useCallback, useEffect, useRef, useState } from "react";

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

// What came of reading one node, and which node it was: the path and selectors it was asked through
interface Read<T> {
  of: string;
  value?: T;
  error?: string;
}

// What a page reading one node needs: the parsed value, whether that node is still being read, what
// went wrong with the last read, and a way to read again. A failed re-read keeps the last good value, so
// the page stays readable under the error. Asked for another node, it starts from nothing, as loading:
// a page showing the node before as this one would send whatever is done on it to the wrong node.
export function useNode<T>(path: string, selectors: string, parse: (node: JcrNode) => T) {
  const doFetch = useAuthenticatedFetch();
  const of = `${path}.${selectors}`;
  const [ read, setRead ] = useState<Read<T>>();
  // The node the page asks for now: an answer about one it asked for before arrives too late to count
  const asked = useRef(of);

  useEffect(() => {
    asked.current = of;
  }, [ of ]);

  const reload = useCallback((): Promise<void> =>
    readNode(doFetch, path, selectors)
      .then(node => {
        if (asked.current === of) {
          setRead({ of, value: parse(node) });
        }
      })
      .catch((error: unknown) => {
        if (asked.current === of) {
          setRead(last => ({ of, value: last?.of === of ? last.value : undefined, error: messageOf(error) }));
        }
      }), [ doFetch, path, selectors, of, parse ]);

  useEffect(() => {
    void reload();
  }, [ reload ]);

  const current = read?.of === of ? read : undefined;
  return { value: current?.value, loading: current === undefined, loadError: current?.error, reload, doFetch };
}
