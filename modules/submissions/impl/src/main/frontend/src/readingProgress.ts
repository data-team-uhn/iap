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

// Which step of a reading to show. Parsing reports only that it ended. Digesting is the gap after
// that, before a job takes the reading. Extraction and validation share the model call, which
// reports nothing until the whole reading is done, so those two are walked on a clock.

export const READING_PHASES = [ "parsing", "digesting", "extraction", "validation" ] as const;

export type ReadingPhase = typeof READING_PHASES[number];

export const PHASE_LABEL: Record<ReadingPhase, string> = {
  parsing: "Parsing",
  digesting: "Digesting",
  extraction: "Extraction",
  validation: "Validation",
};

// After the job has had the reading this long, the step moves from extraction to validation. The
// call itself does not say when one becomes the other.
const EXTRACTION_WINDOW_MS = 45_000;

export interface ReadingSignals {
  parsed?: boolean;
  reading?: boolean;
}

// Which step is the current one. `sinceReadingMs` is how long the job has held the reading.
export function getPhase(signals: ReadingSignals, sinceReadingMs: number): ReadingPhase {
  if (signals.parsed !== true) {
    return "parsing";
  }
  if (signals.reading !== true) {
    return "digesting";
  }
  return sinceReadingMs < EXTRACTION_WINDOW_MS ? "extraction" : "validation";
}

// Which of the two steps that can actually fail is the one that did. A parse the daemon rejected
// stops on parsing. Anything else stopped while the model was being asked, which is extraction.
// Digesting and validation have no failure of their own.
export function getFailedPhase(
  extraction: { status: string; parsed?: boolean; retryable?: boolean },
): ReadingPhase | undefined {
  if (extraction.status !== "failed") {
    return undefined;
  }
  if (extraction.retryable === true || extraction.parsed === false) {
    return "parsing";
  }
  return "extraction";
}
