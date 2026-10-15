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

// Reading and editing conditions as the conditions module stores them (see docs/conditions.md). This holds the
// catalogs of comparators and aggregates, and what an operand holds. It turns a stored condition into a draft a
// builder edits, a draft into the content a replaceContent event writes, and either into words. Operand sources are
// pluggable: a module offering one says what its operands hold and how they read. No React, no fetch.

import { isDecimalNumber, isWholeNumber } from "@iap/frontend-commons/numberText";
import { isNode, type SerializedNode } from "@iap/frontend-commons/serializedNode";

/** The comparison types of the conditions module's OperandType. */
export type ValueType = "text" | "long" | "double" | "decimal" | "boolean" | "date";

/** One entry of a list to pick from. */
export interface Choice {
  /** What is stored when it is picked. */
  value: string;
  /** What the list shows for it. */
  label: string;
}

/**
 * What an operand holds, as far as its source knows. A source that cannot tell leaves a field out. An unknown type
 * follows the other side of the comparison, and an unknown multiplicity allows one value or several.
 */
export interface OperandShape {
  /** The type of its values. */
  type?: ValueType;
  /** Whether it holds several values rather than one. */
  multiple?: boolean;
  /** The values it can hold, when they are a known list, such as a question's options. */
  choices?: Choice[];
}

/**
 * Where an operand's values come from at evaluation time, as the `source` an OperandResolver serves. A module offers
 * one for each kind of thing its conditions can read, such as the answer to a question.
 */
export interface OperandSource {
  /** The `source` stored on the operand. */
  name: string;
  /** What choosing it reads as, e.g. "The answer to a question". */
  label: string;
  /**
   * What its `value` names, e.g. "Question", when it names something to read. A source without one reads the same
   * thing whatever the value, such as the tag list.
   */
  valueLabel?: string;
  /** Whether the builder offers it as something to compare; one that is not is still shown. Offered unless false. */
  offered?: boolean;
  /** What its value can name, picked from a list, when the source names something listed, such as a question. */
  candidates?: Choice[];
  /** What an operand of this source holds, given what its `value` names. */
  shape: (value: string[]) => OperandShape;
  /** How an operand of this source reads in a sentence, e.g. "the answer to “Age”". */
  describe: (value: string[]) => string;
}

/** A way to compare two operands, as the conditions module evaluates it. */
export interface Comparator {
  /** The `comparator` stored on the condition. */
  name: string;
  /** How it reads in a sentence and in the list of comparisons, e.g. "is at least". */
  phrase: string;
  /** How it reads before a single value, when that differs from `phrase`. */
  singleValuePhrase?: string;
  /** Takes no second operand, e.g. "is empty". */
  unary?: boolean;
  /** Compares one value with one other, by their order. */
  ordering?: boolean;
  /** Compares the values of an operand holding several with some values. */
  sets?: boolean;
}

/** Every comparator the conditions module evaluates, in the order a builder offers them. */
export const COMPARATORS: Comparator[] = [
  { name: "equals", phrase: "is" },
  { name: "not equals", phrase: "is not" },
  { name: "less than", phrase: "is less than", ordering: true },
  { name: "less or equal", phrase: "is at most", ordering: true },
  { name: "greater than", phrase: "is more than", ordering: true },
  { name: "greater or equal", phrase: "is at least", ordering: true },
  { name: "is empty", phrase: "is empty", unary: true },
  { name: "is not empty", phrase: "is not empty", unary: true },
  { name: "includes", phrase: "includes all of", singleValuePhrase: "includes", sets: true },
  { name: "includes any", phrase: "includes any of", singleValuePhrase: "includes", sets: true },
  { name: "excludes", phrase: "includes none of", singleValuePhrase: "does not include", sets: true },
  { name: "excludes any", phrase: "does not include all of", singleValuePhrase: "does not include", sets: true },
];

/**
 * A way to fold an operand's values into one, such as counting them. The result is of the type the aggregate outputs:
 * a fixed one, or the type of the values it folds ("same").
 */
export interface Aggregate {
  /** The `aggregate` stored on the operand. */
  name: string;
  /** What choosing it reads as. */
  label: string;
  /** What it reads as before the operand it folds. */
  phrase: string;
  /** The types of values it can fold. */
  accepts: ValueType[] | "any";
  /** The type of the one value it gives. */
  output: ValueType | "same";
}

/** Every aggregate the conditions module evaluates. */
export const AGGREGATES: Aggregate[] = [
  { name: "count", label: "The number of values", phrase: "the number of values in", accepts: "any", output: "long" },
];

/** The source of an operand whose values are written in the condition itself. A stored operand without one has it. */
export const LITERAL = "literal";

const BOOLEAN_CHOICES: Choice[] = [ { value: "true", label: "Yes" }, { value: "false", label: "No" } ];

/**
 * The choices, plus the current value when it is none of them. A condition naming something this model does not
 * know then shows it and keeps it, rather than losing it to the first choice.
 *
 * @param choices the choices known
 * @param current the value picked now, if any
 * @param labelOf how to label the current value when it is added; by default, as it is
 * @returns the choices, with the current value last when it was added
 */
export const withCurrent = (choices: Choice[], current?: string, labelOf = (value: string) => value): Choice[] =>
  (current === undefined || current === "" || choices.some(choice => choice.value === current)
    ? choices : [ ...choices, { value: current, label: labelOf(current) } ]);

/** The values an operand of this shape can be compared with, when they are a known list: Yes and No for a boolean. */
export const choicesOf = (shape: OperandShape): Choice[] =>
  (shape.type === "boolean" ? BOOLEAN_CHOICES : shape.choices ?? []);

const ORDERED: ValueType[] = [ "long", "double", "decimal", "date" ];

/** The comparator of that name, or undefined when this model does not know it. */
export const comparatorOf = (name: string): Comparator | undefined => COMPARATORS.find(item => item.name === name);

/** The aggregate of that name, or undefined when there is none or this model does not know it. */
export const aggregateOf = (name?: string): Aggregate | undefined => AGGREGATES.find(item => item.name === name);

/**
 * The comparators that can hold for an operand of this shape. An ordering needs an operand holding one value, of a
 * type with an order when the type is known. A set comparison needs an operand that may hold several.
 */
export const comparatorsFor = (shape: OperandShape): Comparator[] => COMPARATORS.filter(comparator =>
  (!comparator.ordering || (shape.multiple !== true && (shape.type === undefined || ORDERED.includes(shape.type))))
  && (!comparator.sets || shape.multiple !== false));

/** The aggregates that can fold an operand of this shape. Only an operand holding several values has any. */
export const aggregatesFor = (shape: OperandShape): Aggregate[] => (shape.multiple !== true ? [] : AGGREGATES
  .filter(aggregate => aggregate.accepts === "any"
    || (shape.type !== undefined && aggregate.accepts.includes(shape.type))));

/** What an operand of this shape holds once folded by the aggregate: one value. Without an aggregate, the shape. */
export function aggregated(shape: OperandShape, aggregate?: Aggregate): OperandShape {
  if (!aggregate) {
    return shape;
  }
  return { type: aggregate.output === "same" ? shape.type : aggregate.output, multiple: false };
}

/**
 * How many values the second operand gives a comparator.
 *
 * @param comparator the comparator, or undefined for one this model does not know
 * @param shape what the first operand holds
 * @returns "none" for a unary comparator; "one" for an ordering, or when the first operand holds one value; and
 *     "several" otherwise
 */
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

/**
 * Why a value entered is not one of a type, if it is not.
 *
 * @param value the value as entered
 * @param type the type it must be; an unknown type takes any text but an empty one
 * @returns a sentence to show under the field, or undefined when the value is fine
 */
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

/** One side of a comparison, as a builder edits it. */
export interface DraftOperand {
  /** The name of its source, or `LITERAL`. */
  source: string;
  /** The values compared, for a literal. For another source, what it names, such as a question. */
  value: string[];
  /** The name of the aggregate folding its values, if any. */
  aggregate?: string;
}

/** A comparison of two operands, as a builder edits it. */
export interface DraftSingle {
  kind: "single";
  /** Tells the conditions of a draft apart while it is edited. It is never written. */
  id: string;
  /** The name of the comparator. */
  comparator: string;
  /** What is compared. */
  a: DraftOperand;
  /**
   * What it is compared with. Kept while a unary comparator is chosen, so that choosing another one brings its values
   * back, but not written.
   */
  b: DraftOperand;
}

/** A group of conditions, as a builder edits it. */
export interface DraftGroup {
  kind: "group";
  /** Tells the conditions of a draft apart while it is edited. It is never written. */
  id: string;
  /** Whether all its conditions must hold, or any one of them. */
  requireAll: boolean;
  conditions: DraftCondition[];
}

/**
 * A condition of a type this model does not know. It is shown and can be removed, but not edited, and a draft
 * holding one cannot be written until it is.
 */
export interface DraftUnknown {
  kind: "unknown";
  id: string;
}

/** Any condition of a draft. */
export type DraftCondition = DraftSingle | DraftGroup | DraftUnknown;

let nextId = 0;
const newId = (): string => `condition-${++nextId}`;

/** A stored property as a list of texts, whether it holds one value or several. Empty values are left out. */
const texts = (value: unknown): string[] => (Array.isArray(value) ? value : [ value ])
  .filter(item => item !== undefined && item !== null && item !== "")
  .map(String);

/** The conditions directly under a stored node, of any condition type. */
const conditionsIn = (node: SerializedNode): SerializedNode[] => Object.values(node)
  .filter(isNode)
  .filter(child => child["sling:resourceSuperType"] === "cond/Condition");

/** The draft of a stored operand. A missing operand gives an empty literal. */
const operandOf = (node: unknown): DraftOperand => {
  const operand = isNode(node) ? node : {};
  const aggregate = texts(operand.aggregate).at(0);
  const source = texts(operand.source).at(0) ?? LITERAL;
  return { source, value: texts(operand.value), ...aggregate ? { aggregate } : {} };
};

/** A new operand reading from the source, which names nothing yet. By default, a literal with no values. */
export const newOperand = (source = LITERAL): DraftOperand => ({ source, value: [] });

/** A new condition with the given source, with the default "equals" operand and no values to compare with. */
export const newSingle = (source: string): DraftSingle =>
  ({ kind: "single", id: newId(), comparator: "equals", a: newOperand(source), b: newOperand() });

/** A new group holding no conditions. By default, all its conditions must hold. */
export const newGroup = (requireAll = true): DraftGroup => ({ kind: "group", id: newId(), requireAll, conditions: [] });

/** The draft of a stored condition of any type, each with a new id. */
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

/**
 * The draft of a stored condition, for a builder to edit. The top is always a group, so a single condition is put in
 * one.
 *
 * @param condition the stored condition, serialized deep enough to hold its operands; absent when there is none
 * @returns the draft, an empty group when there is no condition, which always holds
 */
export function draftOf(condition?: SerializedNode): DraftGroup {
  if (!condition) {
    return newGroup();
  }
  const draft = draftConditionOf(condition);
  return draft.kind === "group" ? draft : { ...newGroup(), conditions: [ draft ] };
}

// ---- What operands hold, and whether a draft can be written

/** The source of that name, or undefined when none of the sources has it. */
export const sourceOf = (sources: OperandSource[], name: string): OperandSource | undefined =>
  sources.find(source => source.name === name);

/** What an operand holds, as its source says, folded when it names an aggregate. A literal says nothing. */
export function shapeOf(operand: DraftOperand, sources: OperandSource[]): OperandShape {
  const shape = sourceOf(sources, operand.source)?.shape(operand.value) ?? {};
  return aggregated(shape, aggregateOf(operand.aggregate));
}

/** What the second operand holds. A literal holds what the first operand holds; another source says for itself. */
export const secondShapeOf = (condition: DraftSingle, sources: OperandSource[]): OperandShape =>
  (condition.b.source === LITERAL ? shapeOf(condition.a, sources) : shapeOf(condition.b, sources));

/** Whether an operand reads from a source whose value names something, such as a question. */
const named = (operand: DraftOperand, sources: OperandSource[]): boolean =>
  operand.source !== LITERAL && sourceOf(sources, operand.source)?.valueLabel !== undefined;

/** Whether what an operand reads has been chosen: a source other than a literal, and what it names, if anything. */
export const isChosen = (operand: DraftOperand, sources: OperandSource[]): boolean =>
  operand.source !== LITERAL && (!named(operand, sources) || operand.value.length > 0);

/** Whether the second operand gives the comparator all it needs: nothing, a chosen source, or valid values. */
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

/** Whether a condition can be written. A group must not be empty. An unknown condition blocks writing. */
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

/**
 * Whether a draft can be written: each condition says all it needs to, and each group below the top holds some. The
 * top may be empty, for no condition at all.
 */
export const isComplete = (draft: DraftGroup, sources: OperandSource[]): boolean =>
  draft.conditions.every(condition => isCompleteCondition(condition, sources));

/** What a draft says, whatever its conditions' ids. Two drafts saying the same thing have the same fingerprint. */
export const fingerprintOf = (draft: DraftGroup): string =>
  JSON.stringify(draft, (key, value: unknown) => (key === "id" ? undefined : value));

// ---- Writing: the content of a replaceContent event

/** How to store a literal value of each type, entered as text. */
const LITERAL_OF: Record<ValueType, (value: string) => string | number | boolean> = {
  text: value => value,
  date: value => value,
  long: value => Number.parseInt(value, 10),
  double: value => Number(value),
  decimal: value => Number(value),
  boolean: value => value === "true",
};

/** The `cond:ConditionOperand` to write for an operand. A literal's values are stored as the type of the shape. */
function operandContent(operand: DraftOperand, shape: OperandShape): SerializedNode {
  const typed = operand.source === LITERAL && shape.type ? LITERAL_OF[shape.type] : String;
  return {
    "jcr:primaryType": "cond:ConditionOperand",
    "source": operand.source,
    "value": operand.value.map(value => typed(value)),
    ...operand.aggregate ? { aggregate: operand.aggregate } : {},
  };
}

/** The node to write for a condition, its children named `condition1`, `condition2` and so on. */
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

/**
 * The condition to write for a draft, as the content of a replaceContent event.
 *
 * @param draft a draft that `isComplete` accepts
 * @param sources the sources its operands may read from, which give the literals their types
 * @returns a `cond:ConditionGroup` node, or null for a draft holding nothing, which always applies
 */
export const contentOf = (draft: DraftGroup, sources: OperandSource[]): SerializedNode | null =>
  (draft.conditions.length === 0 ? null : conditionContent(draft, sources));

// ---- Reading

/** A value as a sentence shows it: a number as it is, anything else in quotes. */
const quoted = (value: string): string => (/^-?\d+(\.\d+)?$/.test(value) ? value : `“${value}”`);

/** An operand in words. `compared` is what the other side holds, whose choices label a literal's values. */
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

/** A comparison in words, e.g. "the answer to “Age” is at least 18". */
function describeSingle(condition: DraftSingle, sources: OperandSource[]): string {
  const comparator = comparatorOf(condition.comparator);
  const singleValuePhrase = condition.b.source === LITERAL && condition.b.value.length === 1
    ? comparator?.singleValuePhrase : undefined;
  const comparatorPhrase = singleValuePhrase ?? comparator?.phrase ?? condition.comparator;
  const phrase = `${describeOperand(condition.a, sources)} ${comparatorPhrase}`;
  if (comparator?.unary) {
    return phrase;
  }
  const compared = condition.a.aggregate ? undefined : sourceOf(sources, condition.a.source)?.shape(condition.a.value);
  return `${phrase} ${describeOperand(condition.b, sources, compared)}`;
}

/**
 * Whether a condition always holds (true) or never does (false), when its shape alone decides. That is so for an
 * empty group, and for a group of one condition that is itself settled.
 */
function settled(condition: DraftCondition): boolean | undefined {
  if (condition.kind !== "group") {
    return undefined;
  }
  if (condition.conditions.length === 0) {
    return condition.requireAll;
  }
  return condition.conditions.length === 1 ? settled(condition.conditions[0]) : undefined;
}

/**
 * A draft condition in words: "always", "never", or its comparisons joined by "and" or "or". A nested group of
 * several conditions is put in parentheses.
 */
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

/** A stored condition in words, as `describeDraft` says it. */
export const describeCondition = (condition: SerializedNode, sources: OperandSource[]): string =>
  describeDraft(draftConditionOf(condition), sources);

/**
 * The line saying when something applies, for a draft as it stands.
 *
 * @returns "Never", "Only when" and the draft in words, or undefined for a draft that always holds
 */
export function whenDraftApplies(draft: DraftGroup, sources: OperandSource[]): string | undefined {
  const known = settled(draft);
  if (known !== undefined) {
    return known ? undefined : "Never";
  }
  return `Only when ${describeDraft(draft, sources)}`;
}

/** The line saying when something applies, for a stored condition, as `whenDraftApplies` says it. */
export const whenApplies = (condition: SerializedNode, sources: OperandSource[]): string | undefined =>
  whenDraftApplies(draftOf(condition), sources);

// ---- Built-in sources

// Each takes `of`, what the module using it calls the thing it reads from, such as "submission"

/**
 * The source reading the tags of what the condition is evaluated for.
 *
 * @param choices the tags to offer, by name and label; with none, a tag is typed in
 * @param of what the module calls the thing tagged, such as "submission"
 */
export const tagsSource = (choices: Choice[] = [], of?: string): OperandSource => ({
  name: "tags",
  label: of ? `The ${of}'s tags` : "The tags",
  shape: () => ({ type: "text", multiple: true, ...choices.length > 0 && { choices } }),
  describe: () => (of ? `the ${of}'s tag list` : "the tag list"),
});

/**
 * The source reading a property of what the condition is evaluated for, named by the operand's value.
 *
 * @param of what the module calls the thing read from, such as "submission"
 */
export const propertySource = (of?: string): OperandSource => ({
  name: "property",
  label: of ? `A property of the ${of}` : "A property",
  valueLabel: "Property",
  shape: () => ({}),
  describe: value => `the ${of ? `${of}'s ` : ""}${value.at(0) ?? "property"}`,
});

/**
 * The source reading a property of what the condition is on itself, such as a question, rather than of what it is
 * evaluated for.
 *
 * @param of what the module calls the thing the condition is on, such as "part"
 */
export const ownPropertySource = (of?: string): OperandSource => ({
  name: "ownProperty",
  label: of ? `A property of this ${of}` : "One of its own properties",
  valueLabel: "Property",
  shape: () => ({}),
  describe: value => `${of ? `this ${of}'s` : "its own"} ${value.at(0) ?? "property"}`,
});

/** The property source, for a module with no name for what it reads from. */
export const PROPERTY_SOURCE = propertySource();

/** The own-property source, for a module with no name for what the condition is on. */
export const OWN_PROPERTY_SOURCE = ownPropertySource();
