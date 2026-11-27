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

import { validateUpload } from "@iap/frontend-commons/fileValidation";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import type { Enqueue } from "@iap/frontend-commons/useWorkQueue";

// How long the browser check of a file may take, in milliseconds. Past it, the file is sent unchecked,
// and the server still checks its type and size.
const CHECK_TIMEOUT_MS = 30_000;

// Why the browser refuses a file before it is sent, or undefined when it may go
export const checkUpload = (file: File, accepted: string[]): Promise<string | undefined> =>
  validateUpload(file, { accepted, timeoutMs: CHECK_TIMEOUT_MS });

export interface QueuedWork {
  // Whether a piece of work is checked or waiting or under way
  busy: boolean;
  // Why the last one failed, until it is dismissed or another starts
  failure?: string;
  dismiss: () => void;
  // Starts `start` in its turn in the queue, once `check` resolves with no reason to refuse, then calls
  // `onDone`
  run: (start: () => Promise<unknown>, options?: {
    check?: Promise<string | undefined>;
    onDone?: () => void;
  }) => void;
}

// What a requirement's component sends, one piece at a time, among the saves of the submission. While a piece is
// under way, leaving the page asks first, since that aborts an upload still on its way. Moving within the app
// does not abort it.
export function useQueuedWork(queueSave: Enqueue): QueuedWork {
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | undefined>(undefined);

  useEffect(() => {
    if (!busy) {
      return undefined;
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy]);

  const run: QueuedWork["run"] = (start, { check = Promise.resolve(undefined), onDone } = {}) => {
    setBusy(true);
    setFailure(undefined);
    check.then(problem => {
      if (problem !== undefined) {
        throw new Error(problem);
      }
      return queueSave(start);
    }).then(
      () => {
        setBusy(false);
        onDone?.();
      },
      (error: unknown) => {
        setBusy(false);
        // Why the browser check refused the file, or the engine's own reason, or why the request failed
        setFailure(messageOf(error));
      },
    );
  };

  return { busy, failure, dismiss: () => setFailure(undefined), run };
}
