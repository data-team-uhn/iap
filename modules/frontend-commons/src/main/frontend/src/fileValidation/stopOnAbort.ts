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

/**
 * Waits for a promise, but rejects with the signal's reason as soon as the signal aborts. The wait
 * ends on time even for a step that does not watch the signal, such as loading a library or a parse
 * in a worker. That step carries on unless something else stops it.
 *
 * @param promise what to wait for
 * @param signal stops the wait; without one, this is the promise itself
 */
export async function stopOnAbort<T>(promise: Promise<T>, signal?: AbortSignal): Promise<T> {
  if (signal === undefined) {
    return promise;
  }
  signal.throwIfAborted();
  let stop: () => void = () => undefined;
  const aborted = new Promise<never>((_resolve, reject) => {
    stop = () => reject(signal.reason as Error);
    signal.addEventListener("abort", stop, { once: true });
  });
  try {
    return await Promise.race([ promise, aborted ]);
  } finally {
    signal.removeEventListener("abort", stop);
  }
}
