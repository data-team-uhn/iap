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

// Picks the component that draws a subject. Each candidate recognizes some subjects, and the most
// confident one wins. A new kind of subject needs only a new candidate.

/**
 * How well a component suits a subject: the component, and a confidence between 0 and 100. A
 * candidate for a narrow case outbids a general one by answering with a higher confidence. Null
 * means the candidate does not recognize the subject.
 *
 * The component must be defined once, at module level. A candidate that creates a new one on each
 * call makes React remount it on every render, losing whatever it held.
 */
export type Candidate<Subject, Component> = (subject: Subject) => [Component, number] | null;

export interface CandidateRegistry<Subject, Component> {
  // Offers a candidate. Offering the same function again does nothing.
  register: (candidate: Candidate<Subject, Component>) => void;
  // Forgets every candidate. Only for tests.
  clear: () => void;
  // The component of the most confident candidate, the first registered winning a tie; null if no
  // candidate recognizes the subject. A candidate that throws is logged and passed over.
  get: (subject: Subject) => Component | null;
}

/**
 * Creates a registry keeping its candidates on `window`.
 *
 * A component may come from a bundle built separately, with its own copy of this module. A module
 * variable would give each copy a registry of its own, and `window` is what the bundles share.
 *
 * @param storeKey the name of the `window` property that holds the candidates; every registry for
 *     one kind of subject must use the same name
 * @return a registry that reads and writes the candidates under `storeKey`
 */
export function createCandidateRegistry<Subject, Component>(storeKey: string): CandidateRegistry<Subject, Component> {
  const candidates = (): Candidate<Subject, Component>[] => {
    const holder = window as unknown as Record<string, Candidate<Subject, Component>[] | undefined>;
    holder[storeKey] ??= [];
    return holder[storeKey];
  };

  const offerOf = (candidate: Candidate<Subject, Component>, subject: Subject) => {
    try {
      return candidate(subject);
    } catch (error) {
      console.error(`Skipping a candidate of [${storeKey}] that failed.`, error);
      return null;
    }
  };

  return {
    register: candidate => {
      if (!candidates().includes(candidate)) {
        candidates().push(candidate);
      }
    },
    clear: () => {
      candidates().length = 0;
    },
    get: subject => {
      let best: Component | null = null;
      let bestConfidence = -1;
      for (const candidate of candidates()) {
        const offer = offerOf(candidate, subject);
        // Strictly greater, so a tie goes to the first registered
        if (offer && offer[1] > bestConfidence) {
          [ best, bestConfidence ] = offer;
        }
      }
      return best;
    },
  };
}
