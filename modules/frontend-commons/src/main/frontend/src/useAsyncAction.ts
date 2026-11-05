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
  // Turns a rejection into what the caller displays for it, such as a sentence. Undefined discards
  // the rejection, for one the caller handles itself or reports elsewhere. No other failure goes
  // unmentioned, so a caller that returns undefined says why.
  onFailure: (error: unknown) => F | undefined;
  // Called once the action has resolved, such as to close the dialog that started it.
  onSuccess?: () => void;
}

// Runs an asynchronous action, and keeps what a UI needs to know about it. `working` is true while the
// action runs. `failure` is what `onFailure` made of the last rejection; undefined before the first
// run, while a run is going, and after a success. `run` clears the last failure and starts the action.
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
    // Two handlers, not a catch, so that an exception from onSuccess is not reported as the action
    // failing. It becomes an unhandled rejection, which shows it as the caller's bug.
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
