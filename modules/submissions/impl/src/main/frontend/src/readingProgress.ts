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

// How far a reading looks, for the bar the submitter watches. Parsing reports only that it ended.
// Digesting is the gap after that, before a job takes the reading. Extraction and validation share
// the model call, which reports nothing until the whole reading is done, so those two are walked
// on a clock and never quite fill until the server says the reading stopped.

export const READING_PHASES = [ "parsing", "digesting", "extraction", "validation" ] as const;

export type ReadingPhase = typeof READING_PHASES[number];

export const PHASE_LABEL: Record<ReadingPhase, string> = {
  parsing: "Parsing",
  digesting: "Digesting",
  extraction: "Extraction",
  validation: "Validation",
};

// How long the bar is allowed to spend looking busy in a phase before it eases off near the end
// of that segment. Parsing can run much longer; it simply stops short of filling.
const PHASE_BUDGET_MS: Record<ReadingPhase, number> = {
  parsing: 90_000,
  digesting: 12_000,
  extraction: 40_000,
  validation: 30_000,
};

// After the job has had the reading this long, the bar moves from extracting to checking. The
// call itself does not say when one becomes the other.
const EXTRACTION_WINDOW_MS = 45_000;

// A phase that is still going never paints as finished. Finished is reserved for a phase we have
// actually left, or for the reading being over.
const CREEP_CAP = 0.92;

export interface ReadingSignals {
  parsed?: boolean;
  reading?: boolean;
}

// Which segment is the current one. `sinceReadingMs` is how long the job has held the reading.
export function phaseFor(signals: ReadingSignals, sinceReadingMs: number): ReadingPhase {
  if (signals.parsed !== true) {
    return "parsing";
  }
  if (signals.reading !== true) {
    return "digesting";
  }
  return sinceReadingMs < EXTRACTION_WINDOW_MS ? "extraction" : "validation";
}

// How full each segment is, in order, from 0 to 100. `elapsedMs` is how long the current phase
// has been the current one.
export function segmentFills(phase: ReadingPhase, elapsedMs: number): number[] {
  const index = READING_PHASES.indexOf(phase);
  return READING_PHASES.map((name, position) => {
    if (position < index) {
      return 100;
    }
    if (position > index) {
      return 0;
    }
    return Math.round(creep(elapsedMs, PHASE_BUDGET_MS[name]) * 100);
  });
}

function creep(elapsedMs: number, budgetMs: number): number {
  const elapsed = Math.max(0, elapsedMs);
  return CREEP_CAP * (1 - Math.exp(-elapsed / budgetMs));
}
