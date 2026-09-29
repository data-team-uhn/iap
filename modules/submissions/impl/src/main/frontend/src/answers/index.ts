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

import { loadExtensions } from "@iap/ui-extension/extensionManager";

// Finds the answer components by asking the repository which ones are declared. A deployment adds a
// question type by registering an `ext:Extension` on this point and shipping the asset it names.
export const ANSWER_COMPONENT_POINT = "AnswerComponent";

// What `loadExtensions` hands back is deliberately discarded. Each asset registers its own
// candidate with the registry as it is evaluated.
let request: Promise<void> | null = null;
let loaded = false;

/**
 * Loads every declared answer component, once.
 */
export async function loadAnswerComponents(): Promise<void> {
  if (loaded) {
    return;
  }
  request ??= loadExtensions(ANSWER_COMPONENT_POINT)
    .then(() => {
      loaded = true;
    })
    .catch((e: unknown) => {
      console.error("Failed to load the answer components", e);
    })
    .finally(() => {
      request = null;
    });
  return request;
}

/**
 * Whether the answer components are available yet.
 * They arrive over HTTP, so the first render of a field happens before any of them is registered.
 *
 * @return {@code false} until the load settles, then {@code true}, however it settled
 */
export function useAnswerComponents(): boolean {
  const [ ready, setReady ] = useState(loaded);
  useEffect(() => {
    let live = true;
    void loadAnswerComponents().finally(() => {
      if (live) {
        setReady(true);
      }
    });
    return () => {
      live = false;
    };
  }, []);
  return ready;
}
