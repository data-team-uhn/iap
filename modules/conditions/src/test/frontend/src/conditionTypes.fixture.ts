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

// The /ConditionTypes catalogue as the server lists its built-ins, and a fetch serving it, the tag catalogue, and the
// candidates a test gives, by the path they are asked for.

const comparator = (name: string, phrase: string, kind: Record<string, boolean | string> = {}) =>
  ({ name, label: phrase, phrase, unary: false, ordering: false, sets: false, ...kind });

export const CATALOGUE = {
  title: "Conditions",
  items: {
    Comparators: [
      comparator("equals", "is"),
      comparator("not equals", "is not"),
      comparator("less than", "is less than", { ordering: true }),
      comparator("less or equal", "is at most", { ordering: true }),
      comparator("greater than", "is more than", { ordering: true }),
      comparator("greater or equal", "is at least", { ordering: true }),
      comparator("is empty", "is empty", { unary: true }),
      comparator("is not empty", "is not empty", { unary: true }),
      comparator("includes", "includes all of", { sets: true, singleValuePhrase: "includes" }),
      comparator("includes any", "includes any of", { sets: true, singleValuePhrase: "includes" }),
      comparator("excludes", "includes none of", { sets: true, singleValuePhrase: "does not include" }),
      comparator("excludes any", "does not include all of", { sets: true, singleValuePhrase: "does not include" }),
    ],
    Aggregators: [
      { name: "count", label: "The number of values", phrase: "the number of values in", accepts: "any",
        output: "long" },
      { name: "sum", label: "The sum of the values", phrase: "the sum of", accepts: [ "decimal", "double", "long" ],
        output: "same" },
    ],
    Sources: [
      { name: "literal", label: "A specific value", phrase: "{label}", offered: false, order: 0 },
      { name: "property", label: "A property", valueLabel: "Property", phrase: "the {label}", offered: true,
        order: 30 },
      { name: "answer", label: "The answer to a question", valueLabel: "Question", phrase: "the answer to “{label}”",
        offered: true, order: 10, candidates: "{+path}.conditionCandidates.json?source=answer" },
      { name: "tags", label: "The tags", phrase: "the tag list", type: "text", multiple: true,
        choices: "/Tags.doc.json", offered: true, order: 20 },
      { name: "ownProperty", label: "One of its own properties", valueLabel: "Property", phrase: "its own {label}",
        offered: false, order: 40 },
    ],
  },
};

export const TAG_CATALOGUE = {
  items: {
    lifecycle: [ { name: "draft", label: "Draft" }, { name: "urgent", label: "Urgent" } ],
    flags: [ { name: "urgent", label: "Urgent" }, { name: "plain" } ],
  },
};

const answer = (url: string, body: unknown, status = 200) => Promise.resolve({
  ok: status < 400, status, url, redirected: false, json: () => Promise.resolve(body),
} as unknown as Response);

/**
 * A fetch serving a catalogue, the tag catalogue, and the candidates given here: by the URL asked for, or for the
 * answer source by the path of the content they are asked about. Anything else is not found.
 */
export function catalogueFetch(candidates: Record<string, unknown[]> = {}, catalogue: unknown = CATALOGUE) {
  return (url: string) => {
    if (url === "/ConditionTypes.doc.json") {
      return answer(url, catalogue);
    }
    if (url in candidates) {
      return answer(url, candidates[url]);
    }
    if (url === "/Tags.doc.json") {
      return answer(url, TAG_CATALOGUE);
    }
    const asked = /^(.*)\.conditionCandidates\.json\?source=answer$/.exec(url)?.[1];
    return asked !== undefined && asked in candidates ? answer(url, candidates[asked]) : answer(url, {}, 404);
  };
}
