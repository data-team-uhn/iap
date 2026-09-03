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

import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, messageOf, RequestError } from "@iap/frontend-commons/requestFailure";

import {
  CATCHER_STATUS_PATH,
  type CatcherStatus,
  type CaughtMessage,
  messagePath,
  parseCatcherStatus,
  parseCaughtMessage,
  type SerializedNode,
} from "./caughtMailModel";

// The caught mail screens' I/O, in one place. Two hooks rather than one: the status and a message
// are independent reads with no shared state and no writes between them, so a single hook would be
// two halves with every caller using one. What they do share — the request shapes and the wording of
// a failure — is here rather than repeated in each screen.

const checkOk = (response: Response): Response => {
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return response;
};

// Every read goes through here, so a failure — an unreachable server, a refused read, an unreadable
// answer — reaches the screen already worded for whoever will read it, and is logged for whoever
// has to diagnose it. The sentence describes the cause only: the screen asking for the message
// already says what was being attempted, so repeating it here would read as an echo.
const reporting = async <T>(exchange: () => Promise<T>): Promise<T> => {
  try {
    return await exchange();
  } catch (error: unknown) {
    throw new Error(describeRequestFailure(error));
  }
};

/** Reads one node, or rejects with a sentence a reader can act on. */
const readNode = (fetchUtil: AuthenticatedFetch, url: string): Promise<SerializedNode> =>
  reporting(async () => {
    const body = (await checkOk(await fetchUtil(url)).json()) as SerializedNode | null;
    if (body === null) {
      // A body that parses to null is well-formed JSON and still not a node. SyntaxError is the
      // shape describeRequestFailure words as the response being unreadable, which is exactly what
      // happened; the detail below reaches the console rather than the screen.
      throw new SyntaxError(`${url} answered with no node`);
    }
    return body;
  });

/**
 * Whether mail is being caught and how much of it has been, read once on mount.
 *
 * A failure is reported as `null` rather than a message: both callers word it differently — the
 * widget says the mailbox is not available to this reader, the browser stays silent because the
 * grid beneath it reports the same unreadable folder in its own terms — and neither wants the other's
 * sentence. The described failure still reaches the console, which is where an administrator
 * diagnosing an unreadable status endpoint will look.
 */
export function useCatcherStatus(): { status: CatcherStatus | null; settled: boolean } {
  const doFetch = useAuthenticatedFetch();
  const [ status, setStatus ] = useState<CatcherStatus | null>(null);
  const [ settled, setSettled ] = useState(false);

  useEffect(() => {
    let cancelled = false;
    readNode(doFetch, CATCHER_STATUS_PATH)
      .then(node => { if (!cancelled) { setStatus(parseCatcherStatus(node)); } })
      .catch(() => { if (!cancelled) { setStatus(null); } })
      .finally(() => { if (!cancelled) { setSettled(true); } });
    return () => { cancelled = true; };
  }, [ doFetch ]);

  return { status, settled };
}

export interface CaughtMessageRead {
  message: CaughtMessage | null;
  /** What to tell the reader, or null when nothing failed. */
  loadError: string | null;
  settled: boolean;
  /** Re-runs the same read, for a retry control. Resolves once it has settled. */
  reload: () => Promise<void>;
}

/**
 * One caught message, re-read whenever the name changes.
 *
 * Read at the default depth: a caught message is a leaf, everything about it is in its own
 * properties, and the bodies are the bulk of it. A null name is a route that names no single
 * message, which is not a read to make and not a failure either.
 */
export function useCaughtMessage(name: string | null): CaughtMessageRead {
  const doFetch = useAuthenticatedFetch();
  const [ message, setMessage ] = useState<CaughtMessage | null>(null);
  const [ loadError, setLoadError ] = useState<string | null>(null);
  const [ settled, setSettled ] = useState(false);

  // Reads are sent in order but can land out of order — a retry can overtake the read it is
  // retrying — so each one carries a token and only the newest is applied
  const newestRead = useRef(0);

  // Both the first read and the retry go through this, so a retry cannot drift from the load it is
  // retrying. It resolves only once the fetch has settled, which is what lets a retry control show
  // the attempt's own progress.
  const reload = useCallback((): Promise<void> => {
    if (name === null) {
      return Promise.resolve();
    }
    newestRead.current += 1;
    const token = newestRead.current;
    // Written with callbacks rather than await deliberately: every setState below then sits in a
    // promise callback, which is what keeps react-hooks/set-state-in-effect satisfied
    return readNode(doFetch, `${messagePath(name)}.json`)
      .then(node => ({ result: parseCaughtMessage(node, name), failure: null as string | null }))
      .catch((cause: unknown) => ({ result: null, failure: messageOf(cause) }))
      .then(({ result, failure }) => {
        if (token !== newestRead.current) {
          return;
        }
        setMessage(result);
        setLoadError(failure);
        setSettled(true);
      });
  }, [ doFetch, name ]);

  // Navigating from one message to another must not leave the previous one on screen under the new
  // one's heading. Done as a render-phase adjustment rather than in the effect, because a setState
  // reached synchronously from an effect is what react-hooks/set-state-in-effect rejects.
  const [ shown, setShown ] = useState(name);
  if (shown !== name) {
    setShown(name);
    setMessage(null);
    setLoadError(null);
    setSettled(false);
  }

  useEffect(() => {
    void reload();
  }, [ reload ]);

  return { message, loadError, settled, reload };
}
