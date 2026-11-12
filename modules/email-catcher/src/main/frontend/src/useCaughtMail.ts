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
import { describeRequestFailure, messageOf, readJson, RequestError } from "@iap/frontend-commons/requestFailure";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

import {
  CATCHER_STATUS_PATH,
  CATCHER_TOGGLE_PATH,
  CATCHING_CHANGED_EVENT,
  type CatcherStatus,
  type CaughtMessage,
  messagePath,
  parseCatcherStatus,
  parseCaughtMessage,
} from "./caughtMailModel";

// The requests of the caught mail screens: the catcher's status, one message, and the switch.

const checkOk = (response: Response): Response => {
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return response;
};

// Rejects a failed read with a sentence for the reader that says the cause, not what was attempted.
// describeRequestFailure also logs the failure to the console.
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
    const body = await readJson<SerializedNode | null>(checkOk(await fetchUtil(url)));
    if (body === null) {
      // describeRequestFailure words a SyntaxError as an unreadable response. This message reaches
      // only the console.
      throw new SyntaxError(`${url} answered with no node`);
    }
    return body;
  });

/**
 * The catcher's status, read on mount and again on each `reload`.
 *
 * `status` is null until the read settles, and null if it failed. The failure is logged to the
 * console.
 */
export function useCatcherStatus(): {
  status: CatcherStatus | null;
  settled: boolean;
  reload: () => void;
} {
  const doFetch = useAuthenticatedFetch();
  const [ status, setStatus ] = useState<CatcherStatus | null>(null);
  const [ settled, setSettled ] = useState(false);
  // Bumped by reload, to read the status again
  const [ asked, setAsked ] = useState(0);

  const reload = useCallback(() => setAsked(previous => previous + 1), []);

  useEffect(() => {
    let cancelled = false;
    readNode(doFetch, CATCHER_STATUS_PATH)
      .then(node => { if (!cancelled) { setStatus(parseCatcherStatus(node)); } })
      .catch(() => { if (!cancelled) { setStatus(null); } })
      .finally(() => { if (!cancelled) { setSettled(true); } });
    return () => { cancelled = true; };
  }, [ doFetch, asked ]);

  return { status, settled, reload };
}

export interface CaughtMessageRead {
  message: CaughtMessage | null;
  /** The sentence that says why the read failed; null if it did not fail. */
  loadError: string | null;
  settled: boolean;
  /** Re-runs the same read, for a retry control. Resolves once it has settled. */
  reload: () => Promise<void>;
}

/**
 * One caught message, read again whenever its node name changes. A null name reads nothing and
 * reports no failure.
 */
export function useCaughtMessage(name: string | null): CaughtMessageRead {
  const doFetch = useAuthenticatedFetch();
  const [ message, setMessage ] = useState<CaughtMessage | null>(null);
  const [ loadError, setLoadError ] = useState<string | null>(null);
  const [ settled, setSettled ] = useState(false);

  // Reads can land out of order, such as a retry overtaking the read it retries. Only the newest
  // read is applied.
  const newestRead = useRef(0);

  // The first read and every retry. It resolves once the read has settled.
  const reload = useCallback((): Promise<void> => {
    if (name === null) {
      return Promise.resolve();
    }
    newestRead.current += 1;
    const token = newestRead.current;
    // Callbacks, not await, so that every setState sits in a promise callback, as
    // react-hooks/set-state-in-effect requires
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

  // Clears the previous message when the name changes. This runs during render, because
  // react-hooks/set-state-in-effect rejects a setState called synchronously in an effect.
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

/**
 * Switches mail catching on and off.
 *
 * `setCatching` resolves once the setting is written, which can be before the catcher has
 * restarted. It rejects if the write fails.
 *
 * @returns `setCatching`, and the sentence that says why the last write failed, or null if it did not
 */
export function useCatcherToggle(): {
  setCatching: (enabled: boolean) => Promise<void>;
  failure: string | null;
} {
  const doFetch = useAuthenticatedFetch();
  const [ failure, setFailure ] = useState<string | null>(null);

  const setCatching = useCallback(async (enabled: boolean): Promise<void> => {
    setFailure(null);
    try {
      const body = new URLSearchParams();
      body.set("enabled", String(enabled));
      checkOk(await doFetch(CATCHER_TOGGLE_PATH, { method: "POST", body }));
      // So that this page shows the change at once
      window.dispatchEvent(new Event(CATCHING_CHANGED_EVENT));
    } catch (error: unknown) {
      setFailure(describeRequestFailure(error));
      throw error;
    }
  }, [ doFetch ]);

  return { setCatching, failure };
}
