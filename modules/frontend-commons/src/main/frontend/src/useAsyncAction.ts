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

interface AsyncActionOptions<F> {
  // Turns a rejection into whatever the caller displays for it: a sentence, a lead and a detail,
  // anything. Returning undefined discards the rejection, which is how a caller either handles a
  // particular one itself or has somewhere else that already reports it - and, being the only way
  // a failure can go unmentioned, is worth a comment wherever it is used.
  onFailure: (error: unknown) => F | undefined;
  // Called once the action has resolved, typically to close whatever asked for it.
  onSuccess?: () => void;
}

// Runs one asynchronous action and keeps the two things a UI needs to know about it: whether it is
// still going, and what to say if it failed.
//
// The failure is whatever shape the caller renders, so the hook stays out of the wording business;
// what it owns is the sequence that is easy to get subtly wrong - clear the last failure before
// trying again, stop reporting as busy on both paths, and never mistake a failure in `onSuccess`
// for a failure of the action itself.
//
// Sample usage:
// const { working, failure, run } = useAsyncAction<string>({
//   onFailure: messageOf,
//   onSuccess: onClose,
// });
// ...
// <Button onClick={() => run(save)} disabled={working}>Save</Button>
// { failure && <Alert severity="error">{failure}</Alert> }
//
export function useAsyncAction<F>({ onFailure, onSuccess }: AsyncActionOptions<F>) {
  const [ working, setWorking ] = useState(false);
  const [ failure, setFailure ] = useState<F>();

  const run = (action: () => Promise<unknown>): void => {
    setWorking(true);
    setFailure(undefined);
    // Two handlers rather than a catch, so that an exception from onSuccess is not reported as the
    // action having failed. It surfaces as an unhandled rejection instead: a caller's bug, left
    // looking like one.
    void action().then(
      () => {
        setWorking(false);
        onSuccess?.();
      },
      (error: unknown) => {
        setWorking(false);
        setFailure(onFailure(error));
      },
    );
  };

  return { working, failure, run };
}
