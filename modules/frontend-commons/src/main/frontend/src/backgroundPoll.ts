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
import { useEffect } from "react";

import { type AuthenticatedFetch, isLoginRedirect } from "./reLogin";

// Requests a page makes on its own, such as a poll for notifications. The server is told they were
// made in the background, so they do not keep a session alive on a page nobody is using. A session
// that has ended stops them, rather than asking a user who may not be there to sign in again.

/** The request header that marks a request as made in the background. */
export const BACKGROUND_HEADER = "X-IAP-Background";

/** What a background request rejects with once the session has ended. */
export class SessionEndedError extends Error {
  constructor() {
    super("The session has ended");
    this.name = "SessionEndedError";
  }
}

/** A fetch for background requests. It rejects with a SessionEndedError once the session has ended. */
export const backgroundFetch: AuthenticatedFetch = async (url, init) => {
  const headers = new Headers(init?.headers);
  headers.set(BACKGROUND_HEADER, "1");
  const response = await fetch(url, { ...init, headers });
  if (response.status === 401 || isLoginRedirect(response)) {
    throw new SessionEndedError();
  }
  return response;
};

/**
 * Runs a task now, then again an interval after each run ends. Unless `whileHidden`, it pauses while
 * the page is hidden, and runs as soon as the page is shown again. A task that rejects with a
 * SessionEndedError stops it until the page is next shown, when the user may have signed in again.
 * Any other rejection waits for the next run.
 *
 * @param task what to run; a stable function, since a new one restarts the poll
 * @param intervalMillis how long to wait after a run before the next
 * @param whileHidden whether to keep running while the page is hidden
 */
export function useBackgroundPoll(task: () => Promise<unknown>, intervalMillis: number, whileHidden: boolean): void {
  useEffect(() => {
    let timer: number | undefined;
    let ended = false;
    // Each start of the poll is a new generation, so that a run still in flight when the poll
    // stopped schedules nothing
    let generation = 0;
    let running = false;
    const stop = () => {
      running = false;
      generation += 1;
      window.clearTimeout(timer);
    };
    const run = (mine: number) => {
      task().then(() => next(mine), (error: unknown) => {
        if (mine === generation && error instanceof SessionEndedError) {
          ended = true;
          stop();
        } else {
          next(mine);
        }
      });
    };
    const next = (mine: number) => {
      if (mine === generation) {
        timer = window.setTimeout(() => run(mine), intervalMillis);
      }
    };
    const follow = () => {
      if (!document.hidden) {
        ended = false;
      }
      if (ended || (document.hidden && !whileHidden)) {
        stop();
      } else if (!running) {
        running = true;
        generation += 1;
        run(generation);
      }
    };
    follow();
    document.addEventListener("visibilitychange", follow);
    return () => {
      document.removeEventListener("visibilitychange", follow);
      stop();
    };
  }, [ task, intervalMillis, whileHidden ]);
}
