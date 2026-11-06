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

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { describeRequestFailure, readJson, RequestError } from "@iap/frontend-commons/requestFailure";

import { type JsonNode, type SchemaChoice, SCHEMAS_URL, schemaChoices } from "./schemaModel";

export interface SchemasOnOffer {
  /** What may be picked; empty until the read has settled, and when it failed. */
  choices: SchemaChoice[];
  /** True until the read settles, one way or the other. */
  loading: boolean;
  /** Why the read failed, in a sentence for the submitter; undefined unless it failed. */
  error?: string;
}

/**
 * The schemas open for submissions, read once when the caller mounts. A schema retired after the read
 * is refused by the server when the submission is raised.
 *
 * @returns what may be picked, and how the read went
 */
export function useSchemas(): SchemasOnOffer {
  const doFetch = useAuthenticatedFetch();
  const [ choices, setChoices ] = useState<SchemaChoice[]>([]);
  const [ error, setError ] = useState<string>();
  const [ settled, setSettled ] = useState(false);

  useEffect(() => {
    let cancelled = false;
    doFetch(SCHEMAS_URL)
      .then(response => {
        if (!response.ok) {
          throw new RequestError(response.status);
        }
        return readJson<unknown>(response).then(body => {
          if (typeof body !== "object" || body === null) {
            throw new SyntaxError("The list of schemas is not a node");
          }
          return body as JsonNode;
        });
      })
      .then(tree => {
        if (!cancelled) {
          setChoices(schemaChoices(tree));
        }
      })
      .catch((failure: unknown) => {
        if (cancelled) {
          return;
        }
        setError(describeRequestFailure(failure));
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
  }, [ doFetch ]);

  return { choices, loading: !settled, error };
}
