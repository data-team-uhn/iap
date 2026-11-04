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

// Which component draws each subject, decided by candidates that each recognize some subjects. A new
// kind of subject is added by registering a candidate for it, and nothing that already exists has to
// learn about it.

/**
 * How well a component suits a subject: the component, and a confidence between 0 and 100. The
 * highest confidence wins, so a candidate answering a narrow case outbids a general one without
 * either having to know about the other. Returning null means "not mine".
 *
 * The component must be defined once, at module level. A candidate that creates a new one on each
 * call makes React remount it on every render, losing whatever it held.
 */
export type Candidate<Subject, Component> = (subject: Subject) => [Component, number] | null;

export interface CandidateRegistry<Subject, Component> {
  // Offers a candidate. Offering the same one again does nothing, so the same module evaluated twice
  // leaves one registration and not several.
  register: (candidate: Candidate<Subject, Component>) => void;
  // Forgets every candidate. Only for tests: registration is otherwise a one-way, load-time act.
  clear: () => void;
  // The component of the most confident candidate, the first registered winning a tie, or null when
  // no candidate recognizes the subject. A candidate that throws is logged and passed over.
  get: (subject: Subject) => Component | null;
}

/**
 * Creates a registry keeping its candidates on `window`.
 *
 * A component may be contributed by a bundle built separately, which has its own copy of this
 * module. A module variable would give each copy a private registry, while `window` is what both
 * builds share.
 *
 * @param storeKey the name of the `window` property holding the candidates; every copy of the
 *     registry for one kind of subject must use the same name
 * @return a registry reading and writing the candidates under `storeKey`
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
