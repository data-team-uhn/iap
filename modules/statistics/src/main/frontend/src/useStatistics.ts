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

import { readComputedAt, readMetrics, STATISTICS_PATH, type Metric } from "./statisticsModel";

// The one place the metrics are fetched. The numbers are worked out on a schedule and kept in the
// repository, so this is a property read: it costs the same on a deployment's first day and its
// thousandth. It is asked once per mount and not polled - the figures change once a night.

/** What the metrics say, while it is being read and once it is known. */
export interface Statistics {
  metrics?: Metric[];
  /** When the figures were worked out, null until that is known or if it never happened. */
  computedAt: Date | null;
  failed: boolean;
}

/** Reads the metrics once, and says whether the read failed rather than showing nothing forever. */
export function useStatistics(): Statistics {
  const [ metrics, setMetrics ] = useState<Metric[]>();
  const [ computedAt, setComputedAt ] = useState<Date | null>(null);
  const [ failed, setFailed ] = useState(false);
  const fetchUtil = useAuthenticatedFetch();

  useEffect(() => {
    let cancelled = false;
    void fetchUtil(STATISTICS_PATH)
      .then(response => (response.ok ? response.json() : Promise.reject(new Error(`${response.status}`))))
      .then(payload => {
        if (!cancelled) {
          setMetrics(readMetrics(payload));
          setComputedAt(readComputedAt(payload));
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

  return { metrics, computedAt, failed };
}
