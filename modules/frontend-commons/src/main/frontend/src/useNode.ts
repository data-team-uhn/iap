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
import { describeRequestFailure, messageOf, readJson, RequestError } from "./requestFailure";

// A node as the repository serializes it
type JcrNode = Record<string, unknown>;

// Reads the JSON of one node, from `<path>.<selectors>.json`. A failure rejects with an Error whose
// message is a sentence for the user.
export async function readNode(doFetch: AuthenticatedFetch, path: string, selectors: string): Promise<JcrNode> {
  try {
    const response = await doFetch(`${path}.${selectors}.json`);
    if (!response.ok) {
      throw new RequestError(response.status);
    }
    return await readJson<JcrNode>(response);
  } catch (error: unknown) {
    throw new Error(describeRequestFailure(error));
  }
}

// What came of reading one node.
interface Read<T> {
  // The path and selectors the node was read through, such as `<path>.1.events`
  of: string;
  // The parsed node; undefined if no read of it has succeeded
  value?: T;
  // Why the last read failed, for the user; undefined if it succeeded
  error?: string;
}

// Reads one node for a page. It returns the parsed `value`, whether the node is still `loading`, why
// the last read failed as `loadError`, a `reload`, and the fetch it reads with. A failed reload keeps
// the last good value, so that the page stays readable under the error. Asked for another node, it
// starts again from loading, so that the page never shows or acts on the old node as the new one.
//
// `parse` must keep its identity across renders, such as a function defined at module level. A new
// one on every render reads the node again on every render.
export function useNode<T>(path: string, selectors: string, parse: (node: JcrNode) => T) {
  const doFetch = useAuthenticatedFetch();
  const of = `${path}.${selectors}`;
  const [ read, setRead ] = useState<Read<T>>();
  // The node the page asks for now. An answer about a node asked for before is dropped.
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
