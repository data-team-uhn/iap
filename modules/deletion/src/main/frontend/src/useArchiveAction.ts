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

import { useState } from "react";

import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { type AuthenticatedFetch, useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { describeOutcome } from "./archiveOutcome";

import type { ActionResponse } from "./archiveApi";

/** A restore or a purge of the archive entry stored at `path`, such as `restoreEntry`. */
export type ArchiveAction = (doFetch: AuthenticatedFetch, path: string) => Promise<ActionResponse>;

interface ArchiveActionOptions {
  /** Called once an entry has been restored or purged, and so no longer exists. */
  onDone: () => void;
  /** Called for any other answer: a conflict, a veto or a failure. Nothing changed in the archive. */
  onRefused?: () => void;
}

/**
 * Restores or purges archive entries, and raises a notice for each answer. A request that failed, or got
 * no answer, is offered again from its notice.
 *
 * `busy` is true while a request is out. `act` resolves once the answer is handled, and never rejects.
 */
export function useArchiveAction({ onDone, onRefused }: ArchiveActionOptions) {
  const doFetch = useAuthenticatedFetch();
  const notify = useNotice();
  const [ busy, setBusy ] = useState(false);

  const act = async (path: string, action: ArchiveAction): Promise<void> => {
    const retry = () => { void act(path, action); };
    setBusy(true);
    let response: ActionResponse;
    try {
      response = await action(doFetch, path);
    } catch {
      // Nothing was decided, so the request can be sent again
      notify({ severity: "error", title: "The request could not be sent.", onRetry: retry });
      return;
    } finally {
      setBusy(false);
    }
    notify(describeOutcome(response, retry));
    if (response.status === "restored" || response.status === "deleted") {
      onDone();
    } else {
      onRefused?.();
    }
  };

  return { busy, act };
}
