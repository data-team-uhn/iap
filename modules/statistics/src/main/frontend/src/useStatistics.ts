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

import { readMetrics, STATISTICS_PATH, type Metric } from "./statisticsModel";

// The one place the metrics are fetched. Every number here is computed on the way out, so this is a
// slow endpoint by design rather than by accident; it is asked once per mount and not polled.

/** What the metrics say, while it is being worked out and once it is known. */
export interface Statistics {
  metrics?: Metric[];
  failed: boolean;
}

/** Reads the metrics once, and says whether the read failed rather than showing nothing forever. */
export function useStatistics(): Statistics {
  const [ metrics, setMetrics ] = useState<Metric[]>();
  const [ failed, setFailed ] = useState(false);
  const fetchUtil = useAuthenticatedFetch();

  useEffect(() => {
    let cancelled = false;
    void fetchUtil(STATISTICS_PATH)
      .then(response => (response.ok ? response.json() : Promise.reject(new Error(`${response.status}`))))
      .then(payload => {
        if (!cancelled) {
          setMetrics(readMetrics(payload));
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          // Said out loud rather than shown as an empty dashboard: "no metrics" and "the metrics could
          // not be read" look identical otherwise, and only one of them is somebody's problem
          console.error("The metrics could not be read", error);
          setFailed(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [fetchUtil]);

  return { metrics, failed };
}
