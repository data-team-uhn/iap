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

import {
  AGGREGATES, aggregated, aggregatesFor, COMPARATORS, comparatorsFor, contentOf, describeCondition, describeDraft,
  draftOf, type DraftGroup, type DraftSingle, isChosen, isComplete, newGroup, newSingle, type OperandSource,
  OWN_PROPERTY_SOURCE, ownPropertySource, PROPERTY_SOURCE, propertySource, shapeOf, tagsSource, valueProblem,
  valuesTaken, whenApplies, whenDraftApplies, withCurrent,
} from "@iap/conditions/conditionModel";

// A source whose operands name a field of a known type, as a module offering one would declare it
const FIELDS: Record<string, { type?: "long" | "text" | "date" | "boolean"; multiple?: boolean }> = {
  age: { type: "long", multiple: false },
  colours: { type: "text", multiple: true },
  due: { type: "date", multiple: false },
  consent: { type: "boolean", multiple: false },
};
const FIELD_SOURCE: OperandSource = {
  name: "field",
  label: "A field",
  valueLabel: "Field",
  shape: value => ({
    ...FIELDS[value.at(0) ?? ""],
    ...value.at(0) === "colours" ? { choices: [ { value: "red", label: "Red" }, { value: "blue", label: "Blue" } ] }
      : {},
  }),
  describe: value => `the field ${value.at(0) ?? "nowhere"}`,
};
const TAGS = tagsSource([ { value: "urgent", label: "Urgent" } ]);
const SOURCES = [ FIELD_SOURCE, TAGS, PROPERTY_SOURCE, OWN_PROPERTY_SOURCE ];

const single = (comparator: string, a: Record<string, unknown>, b?: Record<string, unknown>) => ({
  "jcr:primaryType": "cond:SingleCondition", "sling:resourceSuperType": "cond/Condition", comparator,
  "operandA": { "jcr:primaryType": "cond:ConditionOperand", ...a },
  ...b ? { operandB: { "jcr:primaryType": "cond:ConditionOperand", ...b } } : {},
});
const group = (requireAll: boolean, children: Record<string, unknown>) => ({
  "jcr:primaryType": "cond:ConditionGroup", "sling:resourceSuperType": "cond/Condition", requireAll, ...children,
});
const field = (name: string) => ({ source: "field", value: [ name ] });

const draftSingle = (a: DraftSingle["a"], comparator: string, b: DraftSingle["b"]): DraftSingle =>
  ({ ...newSingle(a.source), a, comparator, b });
const rooted = (...conditions: DraftGroup["conditions"]): DraftGroup => ({ ...newGroup(), conditions });

describe("conditionModel", () => {
  describe("the catalogs", () => {
    it("names every comparator and aggregate the conditions module evaluates", () => {
      expect(COMPARATORS.map(comparator => comparator.name)).toEqual([ "equals", "not equals", "less than",
        "less or equal", "greater than", "greater or equal", "is empty", "is not empty", "includes", "includes any",
        "excludes", "excludes any" ]);
      expect(AGGREGATES.map(aggregate => aggregate.name)).toEqual([ "count" ]);
    });

    it("offers ordering for single values that have an order, and sets for several", () => {
      const names = (shape: Parameters<typeof comparatorsFor>[0]) => comparatorsFor(shape).map(item => item.name);
      expect(names({ type: "long", multiple: false })).toEqual([ "equals", "not equals", "less than",
        "less or equal", "greater than", "greater or equal", "is empty", "is not empty" ]);
      expect(names({ type: "text", multiple: false })).toEqual([ "equals", "not equals", "is empty",
        "is not empty" ]);
      expect(names({ type: "date", multiple: true })).toEqual([ "equals", "not equals", "is empty", "is not empty",
        "includes", "includes any", "excludes", "excludes any" ]);
      // Nothing known, anything goes
      expect(names({})).toHaveLength(COMPARATORS.length);
    });

    it("folds several values into one of the type the aggregate outputs, as the evaluator does", () => {
      expect(aggregatesFor({ type: "text", multiple: false })).toEqual([]);
      expect(aggregatesFor({ type: "text", multiple: true })).toEqual(AGGREGATES);
      // Only where several values are known to be held
      expect(aggregatesFor({})).toEqual([]);
      // count's output is fixed: a whole number, whatever it counts
      expect(aggregated({ type: "date", multiple: true, choices: [] }, AGGREGATES[0]))
        .toEqual({ type: "long", multiple: false });
      expect(aggregated({ type: "text" })).toEqual({ type: "text" });
      // One folding into the type it folds keeps it
      expect(aggregated({ type: "double", multiple: true }, { ...AGGREGATES[0], output: "same" }))
        .toEqual({ type: "double", multiple: false });
    });

    it("keeps an aggregate to the types it accepts", () => {
      const numeric = { name: "sum", label: "The total", phrase: "the total of", accepts: [ "long" as const ],
        output: "same" as const };
      AGGREGATES.push(numeric);
      try {
        expect(aggregatesFor({ type: "long", multiple: true })).toContain(numeric);
        expect(aggregatesFor({ type: "text", multiple: true })).not.toContain(numeric);
        expect(aggregatesFor({ multiple: true })).not.toContain(numeric);
      } finally {
        AGGREGATES.pop();
      }
    });

    it("says how many values a comparison takes", () => {
      const of = (name: string) => COMPARATORS.find(item => item.name === name);
      expect(valuesTaken(of("is empty"), { multiple: true })).toBe("none");
      expect(valuesTaken(of("less than"), {})).toBe("one");
      expect(valuesTaken(of("includes"), { multiple: false })).toBe("several");
      expect(valuesTaken(of("equals"), { multiple: false })).toBe("one");
      expect(valuesTaken(of("equals"), { multiple: true })).toBe("several");
      // Unknown until it is known, one
      expect(valuesTaken(of("equals"), {})).toBe("one");
      expect(valuesTaken(undefined, {})).toBe("several");
    });

    it("says what is wrong with a value of a type", () => {
      expect(valueProblem("12", "long")).toBeUndefined();
      expect(valueProblem("1.5", "long")).toBe("Enter a whole number.");
      expect(valueProblem("1.5", "double")).toBeUndefined();
      expect(valueProblem(" ", "decimal")).toBe("Enter a number.");
      // As the fields editor refuses them: too large to hold exactly, or not typed as a decimal
      expect(valueProblem("9007199254740993", "long")).toBe("Enter a whole number.");
      expect(valueProblem("0x10", "double")).toBe("Enter a number.");
      expect(valueProblem("2026-09-29", "date")).toBeUndefined();
      expect(valueProblem("2026-13-45", "date")).toBe("Enter a date.");
      expect(valueProblem("soon", "date")).toBe("Enter a date.");
      expect(valueProblem("true", "boolean")).toBeUndefined();
      expect(valueProblem("maybe", "boolean")).toBe("Choose yes or no.");
      expect(valueProblem("", "text")).toBe("Enter a value.");
      expect(valueProblem("x")).toBeUndefined();
    });
  });

  describe("drafts", () => {
    it("reads a stored condition as a group at the top", () => {
      expect(draftOf().conditions).toEqual([]);
      const draft = draftOf(single("is empty", field("age")));
      expect(draft.requireAll).toBe(true);
      expect(draft.conditions).toMatchObject([ { kind: "single", comparator: "is empty",
        a: { source: "field", value: [ "age" ] }, b: { source: "literal", value: [] } } ]);

      const stored = group(false, {
        "second": single("includes", { source: "tags", aggregate: "count" }),
        "first": single("equals", field("age"), { value: [ 3 ] }),
        "note": { "jcr:primaryType": "nt:unstructured" },
      });
      const read = draftOf(stored);
      expect(read.requireAll).toBe(false);
      expect(read.conditions).toMatchObject([ { a: { source: "tags", value: [], aggregate: "count" } },
        { b: { value: [ "3" ] } } ]);
      expect(draftOf(group(true, { odd: { "jcr:primaryType": "cond:Strange",
        "sling:resourceSuperType": "cond/Condition" } })).conditions).toMatchObject([ { kind: "unknown" } ]);
    });

    it("folds what the first operand holds when it says so", () => {
      expect(shapeOf({ source: "field", value: [ "colours" ] }, SOURCES)).toMatchObject({ multiple: true });
      expect(shapeOf({ source: "field", value: [ "colours" ], aggregate: "count" }, SOURCES))
        .toEqual({ type: "long", multiple: false });
      expect(shapeOf({ source: "nowhere", value: [] }, SOURCES)).toEqual({});
    });

    it("can be written once each condition says all it needs to", () => {
      const complete = (...conditions: DraftGroup["conditions"]) => isComplete(rooted(...conditions), SOURCES);
      const age = { source: "field", value: [ "age" ] };
      expect(complete()).toBe(true);
      expect(complete(draftSingle(age, "equals", { source: "literal", value: [ "3" ] }))).toBe(true);
      expect(complete(draftSingle(age, "equals", { source: "literal", value: [ "three" ] }))).toBe(false);
      expect(complete(draftSingle(age, "equals", { source: "literal", value: [] }))).toBe(false);
      expect(complete(draftSingle(age, "is empty", { source: "literal", value: [] }))).toBe(true);
      // Naming nothing to read, or reading nothing at all
      expect(complete(draftSingle({ source: "field", value: [] }, "is empty", { source: "literal", value: [] })))
        .toBe(false);
      expect(complete(draftSingle({ source: "literal", value: [] }, "is empty", { source: "literal", value: [] })))
        .toBe(false);
      // Several values, all of the type compared
      const colours = { source: "field", value: [ "colours" ] };
      expect(complete(draftSingle(colours, "includes", { source: "literal", value: [ "red", "blue" ] }))).toBe(true);
      expect(complete(draftSingle(colours, "includes", { source: "literal", value: [] }))).toBe(false);
      // Compared with what another source reads
      expect(complete(draftSingle(age, "less than", { source: "field", value: [ "age" ] }))).toBe(true);
      expect(complete(draftSingle(age, "less than", { source: "field", value: [] }))).toBe(false);
      expect(complete(draftSingle(colours, "includes", { source: "tags", value: [] }))).toBe(true);
      // Groups hold something, and kinds nobody knows cannot be written
      expect(complete(newGroup())).toBe(false);
      const empty = draftSingle(age, "is empty", { source: "literal", value: [] });
      expect(complete({ ...newGroup(), conditions: [ empty ] })).toBe(true);
      expect(complete({ kind: "unknown", id: "x" })).toBe(false);
    });
  });

  describe("writing", () => {
    it("writes nothing for a draft holding nothing", () => {
      expect(contentOf(newGroup(), SOURCES)).toBeNull();
    });

    it("writes a draft as the content replaceContent expects, literals typed as what they are compared with", () => {
      const draft = rooted(
        draftSingle({ source: "field", value: [ "age" ] }, "greater or equal", { source: "literal", value: [ "18" ] }),
        { ...newGroup(false), conditions: [
          draftSingle({ source: "field", value: [ "consent" ] }, "equals", { source: "literal", value: [ "true" ] }),
          draftSingle({ source: "field", value: [ "due" ] }, "less than",
            { source: "literal", value: [ "2026-09-29" ] }),
          draftSingle({ source: "tags", value: [], aggregate: "count" }, "equals",
            { source: "literal", value: [ "2" ] }),
          draftSingle({ source: "property", value: [ "status" ] }, "equals", { source: "literal", value: [ "7" ] }),
          draftSingle({ source: "tags", value: [] }, "is empty", { source: "literal", value: [ "left over" ] }),
          { kind: "unknown", id: "ignored" },
        ] },
        draftSingle({ source: "field", value: [ "age" ] }, "less than", { source: "field", value: [ "age" ] }),
      );
      const operand = (source: string, value: unknown[], extra = {}) =>
        ({ "jcr:primaryType": "cond:ConditionOperand", source, value, ...extra });
      expect(contentOf(draft, SOURCES)).toEqual({
        "jcr:primaryType": "cond:ConditionGroup", "requireAll": true,
        "condition1": { "jcr:primaryType": "cond:SingleCondition", "comparator": "greater or equal",
          "operandA": operand("field", [ "age" ]), "operandB": operand("literal", [ 18 ]) },
        "condition2": { "jcr:primaryType": "cond:ConditionGroup", "requireAll": false,
          "condition1": { "jcr:primaryType": "cond:SingleCondition", "comparator": "equals",
            "operandA": operand("field", [ "consent" ]), "operandB": operand("literal", [ true ]) },
          "condition2": { "jcr:primaryType": "cond:SingleCondition", "comparator": "less than",
            "operandA": operand("field", [ "due" ]), "operandB": operand("literal", [ "2026-09-29" ]) },
          "condition3": { "jcr:primaryType": "cond:SingleCondition", "comparator": "equals",
            "operandA": operand("tags", [], { aggregate: "count" }), "operandB": operand("literal", [ 2 ]) },
          // Nothing known of a property: its values stay text, which the evaluator reads as the other side's
          "condition4": { "jcr:primaryType": "cond:SingleCondition", "comparator": "equals",
            "operandA": operand("property", [ "status" ]), "operandB": operand("literal", [ "7" ]) },
          "condition5": { "jcr:primaryType": "cond:SingleCondition", "comparator": "is empty",
            "operandA": operand("tags", []) },
        },
        "condition3": { "jcr:primaryType": "cond:SingleCondition", "comparator": "less than",
          "operandA": operand("field", [ "age" ]), "operandB": operand("field", [ "age" ]) },
      });
    });

    it("writes numbers of every kind as numbers", () => {
      const withType = (type: "double" | "decimal"): OperandSource =>
        ({ ...FIELD_SOURCE, shape: () => ({ type, multiple: false }) });
      for (const type of [ "double", "decimal" ] as const) {
        const draft = rooted(draftSingle({ source: "field", value: [ "x" ] }, "equals",
          { source: "literal", value: [ "1.5" ] }));
        expect(contentOf(draft, [ withType(type) ])).toMatchObject({ condition1: { operandB: { value: [ 1.5 ] } } });
      }
    });
  });

  describe("reading", () => {
    it("reads operands as their sources say, and literals by the labels of what they are compared with", () => {
      const described = (condition: Record<string, unknown>) => describeCondition(condition, SOURCES);
      expect(described(single("includes", { source: "tags" }, { value: [ "urgent", "other" ] })))
        .toBe("the tag list includes all of “Urgent”, “other”");
      expect(described(single("equals", field("colours"), { value: [ "red" ] })))
        .toBe("the field colours is “Red”");
      // One value, as plainly as it reads
      expect(described(single("includes any", { source: "tags" }, { value: [ "urgent" ] })))
        .toBe("the tag list includes “Urgent”");
      expect(described(single("excludes any", { source: "tags" }, { source: "tags" })))
        .toBe("the tag list does not include all of the tag list");
      expect(described(single("greater or equal", { ...field("colours"), aggregate: "count" }, { value: [ 2 ] })))
        .toBe("the number of values in the field colours is at least 2");
      expect(described(single("equals", { source: "ownProperty", value: [ "optionsFrom" ] }, { value: [] })))
        .toBe("its own optionsFrom is nothing");
      expect(described(single("equals", { source: "ownProperty" }, { source: "property" })))
        .toBe("its own property is the property");
      expect(described(single("sounds like", { source: "elsewhere", value: [ "x" ] }, { value: [ "y" ] })))
        .toBe("“x” sounds like “y”");
    });

    it("brackets the groups inside a group, and reads a group of one as what it holds", () => {
      const nested = group(true, {
        a: single("is empty", field("age")),
        b: group(false, { c: single("is empty", field("due")), d: single("is not empty", field("due")) }),
        e: group(false, { f: single("is empty", field("consent")) }),
      });
      expect(describeCondition(nested, SOURCES)).toBe("the field age is empty and "
        + "(the field due is empty or the field due is not empty) and the field consent is empty");
      expect(describeDraft({ kind: "unknown", id: "x" }, SOURCES)).toBe("a condition of an unknown kind holds");
    });

    it("words when something applies", () => {
      expect(whenApplies(group(true, {}), SOURCES)).toBeUndefined();
      // What holds whatever it is evaluated on, by its shape alone
      expect(whenApplies(group(false, { only: group(true, {}) }), SOURCES)).toBeUndefined();
      expect(whenApplies(group(true, { only: group(false, {}) }), SOURCES)).toBe("Never");
      expect(describeCondition(group(true, { a: group(false, {}), b: single("is empty", field("age")) }), SOURCES))
        .toBe("never and the field age is empty");
      expect(whenApplies(group(false, {}), SOURCES)).toBe("Never");
      expect(whenApplies(single("is empty", field("age")), SOURCES)).toBe("Only when the field age is empty");
      expect(whenDraftApplies(newGroup(), SOURCES)).toBeUndefined();
    });

    it("keeps what is chosen among the choices, by what it is called", () => {
      const choices = [ { value: "a", label: "A" } ];
      expect(withCurrent(choices, "a")).toBe(choices);
      expect(withCurrent(choices)).toBe(choices);
      expect(withCurrent(choices, "")).toBe(choices);
      expect(withCurrent(choices, "b")).toEqual([ ...choices, { value: "b", label: "b" } ]);
      expect(withCurrent(choices, "b", value => value.toUpperCase()))
        .toEqual([ ...choices, { value: "b", label: "B" } ]);
    });

    it("names what the built-in sources read, as the module using them calls it", () => {
      expect([ tagsSource([], "submission").label, propertySource("submission").label,
        ownPropertySource("part").label ]).toEqual([ "The submission's tags", "A property of the submission",
        "A property of this part" ]);
      expect([ tagsSource([], "submission").describe([]), propertySource("submission").describe([ "status" ]),
        ownPropertySource("part").describe([ "optionsFrom" ]) ]).toEqual([ "the submission's tag list",
        "the submission's status", "this part's optionsFrom" ]);
      expect([ tagsSource().label, PROPERTY_SOURCE.label, OWN_PROPERTY_SOURCE.label ])
        .toEqual([ "The tags", "A property", "One of its own properties" ]);
    });

    it("knows when what an operand reads has been chosen", () => {
      expect(isChosen({ source: "field", value: [] }, SOURCES)).toBe(false);
      expect(isChosen({ source: "field", value: [ "age" ] }, SOURCES)).toBe(true);
      expect(isChosen({ source: "tags", value: [] }, SOURCES)).toBe(true);
      expect(isChosen({ source: "literal", value: [ "x" ] }, SOURCES)).toBe(false);
    });

    it("offers tags as text, with their labels when they are known", () => {
      expect(tagsSource().shape([])).toEqual({ type: "text", multiple: true });
      expect(TAGS.shape([])).toMatchObject({ choices: [ { value: "urgent", label: "Urgent" } ] });
      expect(PROPERTY_SOURCE.shape([ "status" ])).toEqual({});
      expect(OWN_PROPERTY_SOURCE.shape([])).toEqual({});
    });
  });
});
