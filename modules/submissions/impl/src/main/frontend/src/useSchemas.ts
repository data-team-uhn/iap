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

import { describeRequestFailure, RequestError } from "@iap/frontend-commons/requestFailure";

import { type JsonNode, type SchemaChoice, SCHEMAS_URL, schemaChoices } from "./schemaModel";

// Reading what a submission may be raised against, once. The parsing is in schemaModel.

export interface SchemasOnOffer {
  /** What may be picked, empty until the read has settled and whenever it failed. */
  choices: SchemaChoice[];
  /** True until the read settles, one way or the other. */
  loading: boolean;
  /** Why the read failed, in the submitter's terms, so a caller can say so rather than show an
   * empty list. */
  error?: string;
}

/**
 * The schemas on offer, read once when the caller mounts.
 *
 * Read once rather than kept current. A caller mounts this while asking the submitter to choose,
 * and a schema retired mid-choice is settled by the server when the submission is raised.
 *
 * @returns what may be picked, and how the read went
 */
export function useSchemas(): SchemasOnOffer {
  const [ choices, setChoices ] = useState<SchemaChoice[]>([]);
  const [ error, setError ] = useState<string>();
  // Settled rather than loading, so that the initial state needs no separate "not started yet"
  const [ settled, setSettled ] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch(SCHEMAS_URL)
      .then(response => {
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return response.json() as Promise<JsonNode>;
      })
      .then(tree => {
        if (!cancelled) {
          setChoices(schemaChoices(tree));
        }
      })
      .catch((failure: unknown) => {
        if (!cancelled) {
          setError(describeRequestFailure(failure));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setSettled(true);
        }
      });
    // A caller unmounted mid-read has nowhere to put the answer
    return () => {
      cancelled = true;
    };
  }, []);

  return { choices, loading: !settled, error };
}
