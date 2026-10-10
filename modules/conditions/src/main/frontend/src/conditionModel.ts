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

// Conditions as the conditions module stores them (see docs/conditions.md), for reading and editing them: the
// catalogs of comparators and aggregates, what an operand holds, a draft a builder edits, the content a
// replaceContent event writes from it, and the words it reads as. Operand sources are pluggable: a module offering
// one says what its operands hold and how they read. No React, no fetch.

import { isDecimalNumber, isWholeNumber } from "@iap/frontend-commons/numberText";
import { isNode, type SerializedNode } from "@iap/frontend-commons/serializedNode";

// The comparison types of the conditions module's OperandType
export type ValueType = "text" | "long" | "double" | "decimal" | "boolean" | "date";

export interface Choice {
  value: string;
  label: string;
}

// What an operand holds, as far as its source knows: an unknown type follows the other side, and an unknown
// multiplicity allows both
export interface OperandShape {
  type?: ValueType;
  multiple?: boolean;
  choices?: Choice[];
}

// Where an operand's values come from at evaluation time, as the `source` an OperandResolver serves
export interface OperandSource {
  name: string;
  // What choosing it reads as, e.g. "The answer to a question"
  label: string;
  // What its `value` names, e.g. "Question", when it names something to read
  valueLabel?: string;
  shape: (value: string[]) => OperandShape;
  describe: (value: string[]) => string;
}

export interface Comparator {
  name: string;
  phrase: string;
  // How it reads before a single value, when that is not the same
  one?: string;
  unary?: boolean;
  // Holds only between two single values
  ordering?: boolean;
  // Compares the values of an operand holding several with some values
  sets?: boolean;
}

export const COMPARATORS: Comparator[] = [
  { name: "equals", phrase: "is" },
  { name: "not equals", phrase: "is not" },
  { name: "less than", phrase: "is less than", ordering: true },
  { name: "less or equal", phrase: "is at most", ordering: true },
  { name: "greater than", phrase: "is more than", ordering: true },
  { name: "greater or equal", phrase: "is at least", ordering: true },
  { name: "is empty", phrase: "is empty", unary: true },
  { name: "is not empty", phrase: "is not empty", unary: true },
  { name: "includes", phrase: "includes all of", one: "includes", sets: true },
  { name: "includes any", phrase: "includes any of", one: "includes", sets: true },
  { name: "excludes", phrase: "includes none of", one: "does not include", sets: true },
  { name: "excludes any", phrase: "does not include all of", one: "does not include", sets: true },
];

// An aggregate folds an operand's values into one, of the type it outputs: fixed, or the type it folds ("same")
export interface Aggregate {
  name: string;
  // What choosing it reads as
  label: string;
  // What it reads as before the operand it folds
  phrase: string;
  accepts: ValueType[] | "any";
  output: ValueType | "same";
}

export const AGGREGATES: Aggregate[] = [
  { name: "count", label: "The number of values", phrase: "the number of values in", accepts: "any", output: "long" },
];

export const LITERAL = "literal";

const BOOLEAN_CHOICES: Choice[] = [ { value: "true", label: "Yes" }, { value: "false", label: "No" } ];

// Choices keeping the one chosen among them when it is none of them, as what a condition says but this model does not
// know is shown by name and kept: by what it is called, or else as it is
export const withCurrent = (choices: Choice[], current?: string, labelOf = (value: string) => value): Choice[] =>
  (current === undefined || current === "" || choices.some(choice => choice.value === current)
    ? choices : [ ...choices, { value: current, label: labelOf(current) } ]);

// The values an operand of this shape can be compared with, when they are a known list
export const choicesOf = (shape: OperandShape): Choice[] =>
  (shape.type === "boolean" ? BOOLEAN_CHOICES : shape.choices ?? []);

const ORDERED: ValueType[] = [ "long", "double", "decimal", "date" ];

export const comparatorOf = (name: string): Comparator | undefined => COMPARATORS.find(item => item.name === name);

export const aggregateOf = (name?: string): Aggregate | undefined => AGGREGATES.find(item => item.name === name);

// The comparators that can hold for an operand of this shape
export const comparatorsFor = (shape: OperandShape): Comparator[] => COMPARATORS.filter(comparator =>
  (!comparator.ordering || (shape.multiple !== true && (shape.type === undefined || ORDERED.includes(shape.type))))
  && (!comparator.sets || shape.multiple !== false));

// The aggregates that can fold an operand of this shape
export const aggregatesFor = (shape: OperandShape): Aggregate[] => (shape.multiple !== true ? [] : AGGREGATES
  .filter(aggregate => aggregate.accepts === "any"
    || (shape.type !== undefined && aggregate.accepts.includes(shape.type))));

// What an operand holds once folded
export function aggregated(shape: OperandShape, aggregate?: Aggregate): OperandShape {
  if (!aggregate) {
    return shape;
  }
  return { type: aggregate.output === "same" ? shape.type : aggregate.output, multiple: false };
}

// How many values the second operand gives a comparator: none, one, or any number
export function valuesTaken(comparator: Comparator | undefined, shape: OperandShape): "none" | "one" | "several" {
  // One this model does not know takes whatever it was given
  if (!comparator) {
    return "several";
  }
  if (comparator.unary) {
    return "none";
  }
  if (comparator.ordering) {
    return "one";
  }
  return comparator.sets || shape.multiple === true ? "several" : "one";
}

// Why a value entered is not one of a type, if it is not
export function valueProblem(value: string, type?: ValueType): string | undefined {
  switch (type) {
    case "long":
      return isWholeNumber(value.trim()) ? undefined : "Enter a whole number.";
    case "double":
    case "decimal":
      return isDecimalNumber(value.trim()) ? undefined : "Enter a number.";
    case "date":
      return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) ? undefined : "Enter a date.";
    case "boolean":
      return [ "true", "false" ].includes(value) ? undefined : "Choose yes or no.";
    case "text":
    case undefined:
      return value === "" ? "Enter a value." : undefined;
  }
}

// ---- Drafts: what a builder edits

export interface DraftOperand {
  source: string;
  value: string[];
  aggregate?: string;
}

export interface DraftSingle {
  kind: "single";
  id: string;
  comparator: string;
  a: DraftOperand;
  b: DraftOperand;
}

export interface DraftGroup {
  kind: "group";
  id: string;
  requireAll: boolean;
  conditions: DraftCondition[];
}

// A condition of a type this model does not know, which can be read and removed but not written
export interface DraftUnknown {
  kind: "unknown";
  id: string;
}

export type DraftCondition = DraftSingle | DraftGroup | DraftUnknown;

let nextId = 0;
const newId = (): string => `condition-${++nextId}`;

const texts = (value: unknown): string[] => (Array.isArray(value) ? value : [ value ])
  .filter(item => item !== undefined && item !== null && item !== "")
  .map(String);

const conditionsIn = (node: SerializedNode): SerializedNode[] => Object.values(node)
  .filter(isNode)
  .filter(child => child["sling:resourceSuperType"] === "cond/Condition");

const operandOf = (node: unknown): DraftOperand => {
  const operand = isNode(node) ? node : {};
  const aggregate = texts(operand.aggregate).at(0);
  const source = texts(operand.source).at(0) ?? LITERAL;
  return { source, value: texts(operand.value), ...aggregate ? { aggregate } : {} };
};

export const newOperand = (source = LITERAL): DraftOperand => ({ source, value: [] });

export const newSingle = (source: string): DraftSingle =>
  ({ kind: "single", id: newId(), comparator: "equals", a: newOperand(source), b: newOperand() });

export const newGroup = (requireAll = true): DraftGroup => ({ kind: "group", id: newId(), requireAll, conditions: [] });

function draftConditionOf(node: SerializedNode): DraftCondition {
  switch (node["jcr:primaryType"]) {
    case "cond:SingleCondition":
      return {
        kind: "single", id: newId(), comparator: String(node.comparator),
        a: operandOf(node.operandA), b: operandOf(node.operandB),
      };
    case "cond:ConditionGroup":
      return {
        kind: "group", id: newId(), requireAll: node.requireAll === true,
        conditions: conditionsIn(node).map(draftConditionOf),
      };
    default:
      return { kind: "unknown", id: newId() };
  }
}

// The draft of a stored condition, always a group at the top: none is an empty one, holding always
export function draftOf(condition?: SerializedNode): DraftGroup {
  if (!condition) {
    return newGroup();
  }
  const draft = draftConditionOf(condition);
  return draft.kind === "group" ? draft : { ...newGroup(), conditions: [ draft ] };
}

// ---- What operands hold, and whether a draft can be written

export const sourceOf = (sources: OperandSource[], name: string): OperandSource | undefined =>
  sources.find(source => source.name === name);

// What the first operand of a condition holds, folded when it says so
export function shapeOf(operand: DraftOperand, sources: OperandSource[]): OperandShape {
  const shape = sourceOf(sources, operand.source)?.shape(operand.value) ?? {};
  return aggregated(shape, aggregateOf(operand.aggregate));
}

// What the second operand holds: a literal is what it is compared with, else what its own source says
export const secondShapeOf = (condition: DraftSingle, sources: OperandSource[]): OperandShape =>
  (condition.b.source === LITERAL ? shapeOf(condition.a, sources) : shapeOf(condition.b, sources));

const named = (operand: DraftOperand, sources: OperandSource[]): boolean =>
  operand.source !== LITERAL && sourceOf(sources, operand.source)?.valueLabel !== undefined;

// Whether what an operand reads has been chosen: a source, and what it names, if it names something
export const isChosen = (operand: DraftOperand, sources: OperandSource[]): boolean =>
  operand.source !== LITERAL && (!named(operand, sources) || operand.value.length > 0);

function secondIsComplete(condition: DraftSingle, sources: OperandSource[]): boolean {
  const taken = valuesTaken(comparatorOf(condition.comparator), shapeOf(condition.a, sources));
  if (taken === "none") {
    return true;
  }
  if (condition.b.source !== LITERAL) {
    return isChosen(condition.b, sources);
  }
  const type = secondShapeOf(condition, sources).type;
  return (taken === "one" ? condition.b.value.length === 1 : condition.b.value.length > 0)
    && condition.b.value.every(value => valueProblem(value, type) === undefined);
}

function isCompleteCondition(condition: DraftCondition, sources: OperandSource[]): boolean {
  switch (condition.kind) {
    case "single":
      return isChosen(condition.a, sources) && secondIsComplete(condition, sources);
    case "group":
      return condition.conditions.length > 0
        && condition.conditions.every(child => isCompleteCondition(child, sources));
    case "unknown":
      return false;
  }
}

// Whether a draft can be written: each condition says all it needs to, and each group holds some
export const isComplete = (draft: DraftGroup, sources: OperandSource[]): boolean =>
  draft.conditions.every(condition => isCompleteCondition(condition, sources));

// What a draft says, whatever its conditions' ids, to tell whether it has changed
export const fingerprintOf = (draft: DraftGroup): string =>
  JSON.stringify(draft, (key, value: unknown) => (key === "id" ? undefined : value));

// ---- Writing: the content of a replaceContent event

const LITERAL_OF: Record<ValueType, (value: string) => string | number | boolean> = {
  text: value => value,
  date: value => value,
  long: value => Number.parseInt(value, 10),
  double: value => Number(value),
  decimal: value => Number(value),
  boolean: value => value === "true",
};

function operandContent(operand: DraftOperand, shape: OperandShape): SerializedNode {
  const typed = operand.source === LITERAL && shape.type ? LITERAL_OF[shape.type] : String;
  return {
    "jcr:primaryType": "cond:ConditionOperand",
    "source": operand.source,
    "value": operand.value.map(value => typed(value)),
    ...operand.aggregate ? { aggregate: operand.aggregate } : {},
  };
}

function conditionContent(condition: DraftSingle | DraftGroup, sources: OperandSource[]): SerializedNode {
  if (condition.kind === "group") {
    return {
      "jcr:primaryType": "cond:ConditionGroup",
      "requireAll": condition.requireAll,
      ...Object.fromEntries(condition.conditions
        .filter(child => child.kind !== "unknown")
        .map((child, at) => [ `condition${at + 1}`, conditionContent(child, sources) ])),
    };
  }
  const unary = comparatorOf(condition.comparator)?.unary === true;
  return {
    "jcr:primaryType": "cond:SingleCondition",
    "comparator": condition.comparator,
    "operandA": operandContent(condition.a, {}),
    ...!unary && { operandB: operandContent(condition.b, secondShapeOf(condition, sources)) },
  };
}

// The condition to write for a complete draft, or null for one holding nothing, which always applies
export const contentOf = (draft: DraftGroup, sources: OperandSource[]): SerializedNode | null =>
  (draft.conditions.length === 0 ? null : conditionContent(draft, sources));

// ---- Reading

const quoted = (value: string): string => (/^-?\d+(\.\d+)?$/.test(value) ? value : `“${value}”`);

function describeOperand(operand: DraftOperand, sources: OperandSource[], compared?: OperandShape): string {
  const source = operand.source === LITERAL ? undefined : sourceOf(sources, operand.source);
  if (source) {
    const aggregate = aggregateOf(operand.aggregate);
    const described = source.describe(operand.value);
    return aggregate ? `${aggregate.phrase} ${described}` : described;
  }
  // A literal compared with a known list of values is shown by their labels
  const labels = new Map(choicesOf(compared ?? {}).map(choice => [ choice.value, choice.label ]));
  return operand.value.map(value => quoted(labels.get(value) ?? value)).join(", ") || "nothing";
}

function describeSingle(condition: DraftSingle, sources: OperandSource[]): string {
  const comparator = comparatorOf(condition.comparator);
  const one = condition.b.source === LITERAL && condition.b.value.length === 1 ? comparator?.one : undefined;
  const phrase = `${describeOperand(condition.a, sources)} ${one ?? comparator?.phrase ?? condition.comparator}`;
  if (comparator?.unary) {
    return phrase;
  }
  const compared = condition.a.aggregate ? undefined : sourceOf(sources, condition.a.source)?.shape(condition.a.value);
  return `${phrase} ${describeOperand(condition.b, sources, compared)}`;
}

// Whether a condition always holds, or never does, whatever it is evaluated on, when that follows from its shape
// alone: an empty group, or a group of one that does
function settled(condition: DraftCondition): boolean | undefined {
  if (condition.kind !== "group") {
    return undefined;
  }
  if (condition.conditions.length === 0) {
    return condition.requireAll;
  }
  return condition.conditions.length === 1 ? settled(condition.conditions[0]) : undefined;
}

export function describeDraft(condition: DraftCondition, sources: OperandSource[]): string {
  const known = settled(condition);
  if (known !== undefined) {
    return known ? "always" : "never";
  }
  switch (condition.kind) {
    case "single":
      return describeSingle(condition, sources);
    case "group": {
      if (condition.conditions.length === 1) {
        return describeDraft(condition.conditions[0], sources);
      }
      return condition.conditions
        .map(child => (child.kind === "group" && child.conditions.length > 1
          ? `(${describeDraft(child, sources)})` : describeDraft(child, sources)))
        .join(condition.requireAll ? " and " : " or ");
    }
    case "unknown":
      return "a condition of an unknown kind holds";
  }
}

// A stored condition in words
export const describeCondition = (condition: SerializedNode, sources: OperandSource[]): string =>
  describeDraft(draftConditionOf(condition), sources);

// When something applies, as the line shown on it for a draft as it stands: nothing for one that always holds
export function whenDraftApplies(draft: DraftGroup, sources: OperandSource[]): string | undefined {
  const known = settled(draft);
  if (known !== undefined) {
    return known ? undefined : "Never";
  }
  return `Only when ${describeDraft(draft, sources)}`;
}

// The same for a stored condition
export const whenApplies = (condition: SerializedNode, sources: OperandSource[]): string | undefined =>
  whenDraftApplies(draftOf(condition), sources);

// ---- Built-in sources

// Built-in sources, reading what a condition is evaluated for as the module using them names it ("submission")

export const tagsSource = (choices: Choice[] = [], of?: string): OperandSource => ({
  name: "tags",
  label: of ? `The ${of}'s tags` : "The tags",
  shape: () => ({ type: "text", multiple: true, ...choices.length > 0 && { choices } }),
  describe: () => (of ? `the ${of}'s tag list` : "the tag list"),
});

export const propertySource = (of?: string): OperandSource => ({
  name: "property",
  label: of ? `A property of the ${of}` : "A property",
  valueLabel: "Property",
  shape: () => ({}),
  describe: value => `the ${of ? `${of}'s ` : ""}${value.at(0) ?? "property"}`,
});

// A property of what the condition is on itself, such as a question, rather than of what it is evaluated for
export const ownPropertySource = (of?: string): OperandSource => ({
  name: "ownProperty",
  label: of ? `A property of this ${of}` : "One of its own properties",
  valueLabel: "Property",
  shape: () => ({}),
  describe: value => `${of ? `this ${of}'s` : "its own"} ${value.at(0) ?? "property"}`,
});

export const PROPERTY_SOURCE = propertySource();

export const OWN_PROPERTY_SOURCE = ownPropertySource();
