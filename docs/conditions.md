# Conditions

Many pieces of content only apply *sometimes*: a schema question shown only when a previous answer
calls for it, a document requirement imposed only on certain kinds of submissions, a review step
needed only when a submission is tagged as sensitive. The **conditions module**
(`modules/conditions`) provides the shared vocabulary for expressing such rules as content, and the
engine deciding whether a rule currently holds. It is deliberately independent of any specific
domain: schemas use it today, and anything else (e.g. workflow gateways) can attach the same
conditions tomorrow.

## The data model

A condition is stored as a child node of the content it guards:

- **`cond:Conditionable`** — a mixin any node type can add, contributing one optional
  `cond:condition` child. In the schemas module both `sch:FormItem` (questions, sections) and
  `sch:Requirement` mix it in.
- **`cond:Condition`** — the abstract base, either of:
  - **`cond:SingleCondition`** — one comparison: a `comparator` name and two operand children,
    `operandA` and `operandB` (`operandB` may be omitted for unary comparators like `is empty`).
  - **`cond:ConditionGroup`** — any number of nested `cond:Condition` children (single conditions
    or further groups), combined with OR by default, or with AND when `requireAll` is `true`.
- **`cond:ConditionOperand`** — one side of a comparison. Its `source` property names the
  resolver that computes the actual values at evaluation time, its multi-valued `value` property
  configures that resolver (see [operand sources](#operand-sources)), and its optional
  `aggregate` property folds the resolved values into a single value before the comparison (see
  [aggregators](#aggregators)).

The corresponding Sling Models live in `io.uhndata.iap.conditions.models`. As with the other
abstract bases in the data model, `resource.adaptTo(Condition.class)` yields the concrete
`SingleCondition`/`ConditionGroup` model, so new condition types can plug in without changing any
caller.

## Evaluating conditions

The `ConditionEvaluator` OSGi service (`io.uhndata.iap.conditions.api`) answers "does this
condition hold?" for a given **context resource** — the piece of content the question is about,
e.g. a submission when deciding which of its schema's requirements apply:

```java
@Reference
private ConditionEvaluator evaluator;
...
boolean satisfied = this.evaluator.isSatisfied(condition, submissionResource);
boolean applies = this.evaluator.applies(requirement, submissionResource);
```

`applies()` is the common shorthand: it evaluates the `cond:condition` guarding any
`Conditionable` model, and returns `true` when there is no condition at all.

Evaluation **fails closed**: a condition that cannot be evaluated — unknown comparator, unknown
operand source, unrecognized condition type — is reported as not satisfied (with a warning in the
log), so content guarded by a broken condition stays hidden instead of leaking. An empty AND group
is satisfied and an empty OR group is not, following the usual empty-conjunction/disjunction
conventions.

`Submission.getMissingRequirements()` already uses the service: requirements, sections and
questions whose condition doesn't hold are not reported as missing.

### Comparison types

There is no declared comparison type: the evaluator **unifies** the types the two sides are known
to hold, then coerces both to the unified type (`text`, `boolean`, `long`, `double`, `decimal` or
`date`; values that cannot be interpreted as it are dropped). What a side is "known to hold" comes
from, in order:

- the type its resolver is authoritative about — an `answer` operand carries the referenced
  question's own declared `dataType`, a `tags` operand is always text;
- otherwise, the stored type of the values themselves — a literal saved as a JCR LONG or a
  `property` holding a date speak for themselves. Plain strings deliberately declare *nothing*:
  a string is the flexible side of a comparison, coerced to whatever the other side calls for.
  When authoring literals in JSON, leave numbers and booleans unquoted so they are stored typed.

A side of unknown type follows the other side; two unknown sides compare as text; two different
numeric types widen to the larger one; anything else mismatched (e.g. a number against a boolean)
cannot be compared and the condition fails closed, with a warning in the log.

### Comparators

The `comparator` property of a single condition names one of the operators below.

| Comparator | Holds when |
| --- | --- |
| `equals` | both operands hold the same set of values |
| `not equals` | the negation of `equals` |
| `less than`, `less or equal`, `greater than`, `greater or equal` | both operands hold exactly one value, ordered accordingly; multi-valued or empty operands never pass |
| `is empty` | the first operand holds no values (the second is ignored) |
| `is not empty` | the first operand holds at least one value (the second is ignored) |
| `includes` | the first operand contains every value of the second |
| `includes any` | the first operand contains at least one value of the second |
| `excludes` | the first operand contains no value of the second |
| `excludes any` | the first operand lacks at least one value of the second |

### Operand sources

Each operand declares where its values come from through its `source` property:

- **`literal`** (the default) — the operand's own `value` property, as stored.
- **`answer`** — the recorded answer to a question. The operand's `value` identifies the question
  either by its UUID or by its path relative to the entity holding the condition definition (e.g.
  `form/age` under the schema version), so two questions sharing a name in different sections
  never collide; the question's own declared `dataType` steers the comparison type unification.
  The answer is any node whose `question` property references that question, searched
  **nearest-scope-first**: the context resource's own subtree is checked before widening,
  one ancestor at a time, to the whole enclosing entity. When the same question is answered in
  several repeated blocks, a condition evaluated inside one block therefore sees that block's own
  answer.
- **`tags`** — the effective tags of the enclosing entity (its own `tags` plus the materialized
  `aggregatedTags` and `inheritedTags`), typically tested with the `includes`/`excludes` family
  against literal tag names. The operand's `value` is ignored.
- **`property`** — an arbitrary metadata property of the enclosing entity, named by the operand's
  `value` — e.g. the workflow-managed `status`, or audit properties like `jcr:createdBy`.

### Aggregators

An operand's optional `aggregate` property folds its resolved value set into a single value
before the comparison — the principled way to apply the single-value ordering comparators to a
value set, e.g. "the options picked in this multi-select answer number at least 3" (`count` +
`greater or equal`).

| Aggregate | Result |
| --- | --- |
| `count` | the number of values, compared as a number regardless of what is counted |
| `sum` | the sum of the values; only applicable when the comparison type is numeric |
| `avg` | the arithmetic mean, computed and compared as a floating point number |
| `min`, `max` | the smallest/largest value by the natural order of the compared type — works for dates too, e.g. "the latest reported visit" |

Aggregating an empty operand yields an empty operand, so absence stays detectable via `is empty`
(and fails ordering comparisons) instead of silently becoming a number. An aggregate with a fixed
result type (`count`, `avg`) takes that side's place in the comparison type unification.

### Adding a new operand source

Operand resolution is an extension point: implement
`io.uhndata.iap.conditions.spi.OperandResolver` and register it as an OSGi component. The
evaluator picks the resolver whose `getSource()` matches the operand's `source` property, and
passes it the operand definition and the context resource. A resolver returns its values in their
natural stored types — coercion is the evaluator's job — declaring the type it is authoritative
about, if any, via `Operand.of(value, type)`. It returns an empty operand (never `null`) when
nothing matches, so `is empty` can test for absence.

```java
@Component
public class WeekdayOperandResolver implements OperandResolver
{
    @Override
    public String getSource()
    {
        return "weekday";
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Resource context)
    {
        return Operand.of(LocalDate.now().getDayOfWeek().toString(), OperandType.TEXT);
    }
}
```

## Example

A schema question shown only to submissions tagged `sensitive` whose "participants" answer is 10
or more:

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

The `participants` answer is compared numerically without any type declaration here: the
referenced question's own `dataType` sets the comparison type, and the unquoted literal `10`
is stored as a number anyway.

## Future work

The nearest-scope answer lookup is a fixed, sensible default. When repeatable sections land, it
will be generalized into a small explicit axis vocabulary with XPath-like semantics — an operand
`scope` (descendants, ancestors, siblings...) and `position` (n-th matched sibling) — so a
condition can address "the previous block's answer" or "the first entry". Those two property
names are reserved on `cond:ConditionOperand` for that purpose.
