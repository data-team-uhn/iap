# Conditions

**Module:** `modules/conditions` · **Bundle:** `iap-conditions` ·
**Packages:** `…conditions.api` (evaluator, `Operand`, `OperandType`, `Operator`,
`Aggregator`), `…conditions.spi` (`OperandResolver`), `…conditions.models`

Content that only applies *sometimes* — a question shown when a previous answer calls
for it, a requirement imposed on certain submissions, a review step needed when a
submission is tagged sensitive. This module provides the vocabulary for expressing
such rules as content and the engine that decides whether one currently holds. It is
domain-independent: schemas use it today, workflow gateways can use it tomorrow.

## Data model

```
cond:Conditionable       mixin, contributes one optional cond:condition child
cond:Condition           abstract, extends data:EntityPart
├── cond:SingleCondition comparator (mandatory) + operandA (mandatory) + operandB
└── cond:ConditionGroup  requireAll (default false) + any number of cond:Condition children
cond:ConditionOperand    source (default "literal") + value (multiple) + aggregate
```

`sch:FormItem` and `sch:Requirement` both mix in `cond:Conditionable`.
`operandB` is autocreated, so it exists as an empty literal operand even for unary
comparators. `adaptTo(Condition.class)` yields the concrete
`SingleCondition`/`ConditionGroup`, so new condition types plug in without touching
callers.

## Evaluating

```java
@Reference
private ConditionEvaluator evaluator;

boolean satisfied = evaluator.isSatisfied(condition, submission);   // Condition, Content
boolean applies   = evaluator.applies(requirement, submission);     // Conditionable, Content
```

Both sides are always models, never raw resources. `applies()` evaluates the
`cond:condition` of any `Conditionable`; a null condition is satisfied, so unguarded
content always applies. `Submission.getMissingRequirements()` is one caller to look
at for reference — requirements, sections and questions whose condition doesn't hold
are not reported as missing.

Evaluation **fails closed**. An unknown comparator, unknown operand source,
unrecognized condition type, or incompatible operand types all yield *not satisfied*,
so content behind a broken condition stays hidden rather than leaking. A
mis-authored definition (bad comparator or aggregate name) is additionally recorded
through `ErrorLogger.logProblem` — nothing is broken, a definition is wrong, and
someone should see it.

Empty groups follow the usual conventions: an empty AND holds, an empty OR does not.

### Comparison types

There is no declared comparison type. The evaluator unifies what each side is known
to hold, coerces both, then compares. Values that cannot be interpreted as the
unified type are dropped.

What a side is known to hold, in order:

1. **What its resolver declares** — an `answer` operand carries the referenced
   question's `dataType`; `tags` is always text; an aggregator with a fixed output
   type (`count` → LONG, `avg` → DOUBLE) overrides both.
2. **The stored type of the values** — `OperandType.infer` recognizes Boolean,
   BigDecimal, Number (Double/Float → DOUBLE, else LONG), Calendar/Date. Plain
   strings declare **nothing** and follow the other side, which is what makes them
   the flexible operand. When authoring JSON literals, leave numbers and booleans
   unquoted so they are stored typed.

Unification (`unify`): unknown follows known; two unknowns compare as `TEXT`; two
numerics widen to `DECIMAL` if either is DECIMAL, otherwise `DOUBLE`; anything else
mismatched is not comparable and fails closed with a warning.

### Comparators

| `comparator` | Holds when |
|---|---|
| `equals` / `not equals` | both operands hold the same set of values, or its negation |
| `less than`, `less or equal`, `greater than`, `greater or equal` | **both** sides hold exactly one value, ordered accordingly; multi-valued or empty never pass |
| `is empty` / `is not empty` | operandA holds no / at least one value (operandB ignored) |
| `includes` / `includes any` | operandA contains every / at least one value of operandB |
| `excludes` / `excludes any` | operandA contains no / lacks at least one value of operandB |

### Operand sources

| `source` | Resolves to |
|---|---|
| `literal` (default) | the operand's own `value`, as stored |
| `answer` | the recorded answer to the question named by `value` |
| `tags` | the enclosing entity's effective tags — `tags` + `aggregatedTags` + `inheritedTags`; `value` ignored |
| `property` | the enclosing entity's property named by `value` — e.g. `status`, `jcr:createdBy` |
| `ownProperty` | the property named by `value` of the content the condition is evaluated on itself, not its entity — e.g. a question's `optionsFrom` |

`answer` has two details worth knowing. The question is identified by UUID or by a
path **relative to the entity holding the operand definition** (e.g. `form/age`
under the schema version), not relative to the context — so questions sharing a name
in different sections never collide. And the answer node (anything whose `question`
property references it) is looked up **nearest-scope-first**: the context's own
subtree, then widening one ancestor at a time up to the enclosing entity. A condition
evaluated inside a repeated block therefore sees that block's own answer. Copied
content (see `copyContent` in [workflows.md](workflows.md)) has its `answer` operands
rewritten to name the copied questions by UUID, which survives the questions being
moved or renamed later. Moving content (see `moveContent` in [workflows.md](workflows.md)) does the same
first for the operands naming a moved question, or one under a moved part, by path; a move into another entity also
has the conditions going along name what they leave behind by identifier.

#A module about to remove or move content asks `ConditionDependencies` (in the API) what depends on it: every name an
`answer` operand may give what the content holds, the operands elsewhere in its entity that use one of them, and the
part each such condition is on. The schema editor refuses to remove a question other parts' conditions depend on
this way.

## Aggregators

An operand's `aggregate` folds its values into one before comparison — the
principled way to use the single-value ordering comparators against a set, e.g.
`count` + `greater or equal` for "at least 3 options picked".

| `aggregate` | Result | Output type |
|---|---|---|
| `count` | number of values, whatever is counted | LONG (fixed) |
| `sum` | sum; numeric comparison types only | comparison type |
| `avg` | arithmetic mean | DOUBLE (fixed) |
| `min` / `max` | extreme by the natural order of the compared type, dates included | comparison type |

Aggregating an empty operand yields an empty operand, so absence stays detectable
with `is empty` instead of silently becoming a number. `sum` against a non-numeric
comparison type returns null, which the evaluator reports as unevaluable.

## Adding an operand source

Implement `OperandResolver` and register it as an OSGi component. The evaluator
matches `getSource()` against the operand's `source` property.

```java
@Component
public class WeekdayOperandResolver implements OperandResolver
{
    @Override
    public String getSource() { return "weekday"; }

    @Override
    public Operand resolve(final ConditionOperand operand, final Content context)
    {
        return Operand.of(LocalDate.now().getDayOfWeek().toString(), OperandType.TEXT);
    }
}
```

Contract: return values in their natural stored types — coercion is the evaluator's
job — declaring the type you are authoritative about via `Operand.of(value, type)`,
or `Operand.of(value)` to leave it open. Return `Operand.EMPTY`, never null, when
nothing matches, so `is empty` can test for absence.

The `Content` model is all you need to walk the repository (`get`, `getParent`,
`getChild`, `getChildren`, `getReference`, `isOfType`).
`OperandResolver.findEnclosingEntity(content)` is the shared helper for the "which
record is this about?" walk, used by both `tags` and `property`.

## Example

A question shown only to submissions tagged `sensitive` with 10 or more participants:

```json
{
  "jcr:primaryType": "sch:Question",
  "text": "Describe the additional consent process",
  "cond:condition": {
    "jcr:primaryType": "cond:ConditionGroup",
    "requireAll": true,
    "tagged": {
      "jcr:primaryType": "cond:SingleCondition",
      "comparator": "includes",
      "operandA": { "source": "tags" },
      "operandB": { "value": ["sensitive"] }
    },
    "largeStudy": {
      "jcr:primaryType": "cond:SingleCondition",
      "comparator": "greater or equal",
      "operandA": { "source": "answer", "value": ["form/participants"] },
      "operandB": { "value": 10 }
    }
  }
}
```

No type is declared anywhere: the referenced question's `dataType` sets the
comparison type, and the unquoted `10` is stored as a number regardless.

## Editing in the browser

The module's frontend (`@iap/conditions`) reads and builds conditions for any module that stores them.

- `conditionModel.ts` is the pure half: the `COMPARATORS` and `AGGREGATES` catalogs, what an operand holds (an
  `OperandShape`: its type, whether it holds several values, and the choices it can be compared with), a draft a
  builder edits (`draftOf` a stored condition, always a group at the top), whether it can be written
  (`isComplete`), the content a `replaceContent` event writes from it (`contentOf`, `null` for no condition), and
  the words it reads as (`describeCondition`, `whenApplies`).
- `ConditionBuilder` edits a draft: groups of which all or any must hold, each condition comparing what a source
  reads, optionally folded by an aggregate, with values of the type it holds, or with what another source reads.
  Comparators are offered by what the first operand holds: ordering ones for one value of a type with an order,
  set ones for several values. `ConditionDialog` edits a stored condition whole and says, as it changes, when what
  it guards will apply, in the `AppliesWhenLine` a module shows where it lists what the condition guards; given an
  `onEdit`, that line is also what is pressed to change the condition.

Operand sources are pluggable, as on the server. An `OperandSource` gives its `name` (the stored `source`), what
choosing it reads as, what its `value` names if anything (`valueLabel`), the `shape` of what its operands hold,
and how they read. The module ships `tagsSource` (given the tag choices `useTagChoices` loads),
`PROPERTY_SOURCE` and `OWN_PROPERTY_SOURCE`; the schemas module adds its `answer` source, whose operands hold the
answers to a question of the version, typed by its `dataType` and offering its options. A source whose operands
name something picked rather than typed, such as a question, passes the builder a function rendering an editor for
it.

Each aggregate says which types it `accepts` and what it outputs, a fixed type or the one it folds (`"same"`),
as the evaluator does; the builder offers one where an operand can hold several values of a type it accepts, and
treats the operand as holding the output from then on. Only `count` is offered so far; `sum`, `avg`, `min` and
`max` are one catalog entry each.

A comparator, source or aggregate the model does not know is shown by its stored name and kept; a condition of a
kind it does not know is shown and can be removed, but a draft holding one cannot be written.

## Future work

The nearest-scope answer lookup is a fixed default. When repeatable sections land it
becomes an explicit axis vocabulary with XPath-like semantics — `scope`
(descendants, ancestors, siblings…) and `position` (n-th matched sibling) — so a
condition can address "the previous block's answer". Those two names are reserved on
`cond:ConditionOperand` by convention; the residual property definition already
admits them.
