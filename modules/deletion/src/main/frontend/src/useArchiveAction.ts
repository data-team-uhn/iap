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

/** A request that restores or purges the archive entry at `path`, such as `restoreEntry`. */
export type ArchiveAction = (doFetch: AuthenticatedFetch, path: string) => Promise<ActionResponse>;

interface ArchiveActionOptions {
  /** Called when the entry was restored or purged. It is no longer in the archive. */
  onDone: () => void;
  /** Called for any other answer: a conflict, a veto or a failure. The archive is unchanged. */
  onRefused?: () => void;
}

/**
 * Restores or purges archive entries, and shows a notice with the answer to each request. When a request
 * fails or gets no answer, its notice offers to send it again.
 *
 * `busy` is true while a request is running. `act` resolves when the answer has been handled, and never
 * rejects.
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
