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

import type { ReactNode } from "react";

import AddOutlinedIcon from "@mui/icons-material/AddOutlined";
import CloseOutlinedIcon from "@mui/icons-material/CloseOutlined";
import {
  Box, Button, IconButton, MenuItem, Paper, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from "@mui/material";

import {
  aggregatesFor, type Choice, comparatorOf, comparatorsFor, type DraftCondition, type DraftGroup, type DraftOperand,
  type DraftSingle, LITERAL, newGroup, newOperand, newSingle, type OperandSource, secondShapeOf, shapeOf, sourceOf,
  valuesTaken, withCurrent,
} from "./conditionModel";
import OperandValueInput from "./OperandValueInput";

// What a source's operand names, entered as the source offering it asks, e.g. a question picked from a list
export interface OperandEditorProps {
  label: string;
  value: string[];
  disabled: boolean;
  onChange: (value: string[]) => void;
}

// Each renders the input, by the source's name; a function rather than a component, so that what the function knows
// can change without the input starting again
export type OperandEditors = Partial<Record<string, (props: OperandEditorProps) => ReactNode>>;

interface Common {
  sources: OperandSource[];
  editors: OperandEditors;
  disabled: boolean;
}

interface ChoiceSelectProps {
  label: string;
  value: string;
  choices: Choice[];
  disabled: boolean;
  onChange: (value: string) => void;
}

function ChoiceSelect({ label, value, choices, disabled, onChange }: ChoiceSelectProps) {
  return (
    <TextField select fullWidth label={label} value={value} disabled={disabled}
      onChange={event => onChange(event.target.value)}>
      { choices.map(choice => <MenuItem key={choice.value} value={choice.value}>{choice.label}</MenuItem>) }
    </TextField>
  );
}

interface SourceSelectProps {
  label: string;
  value: string;
  sources: OperandSource[];
  // Whether the operand can hold values of its own
  literal?: boolean;
  disabled: boolean;
  onChange: (source: string) => void;
}

function SourceSelect({ label, value, sources, literal, disabled, onChange }: SourceSelectProps) {
  const offered = [ ...literal ? [ { name: LITERAL, label: "A value" } ] : [], ...sources ];
  return (
    <ChoiceSelect label={label} value={value} disabled={disabled} onChange={onChange}
      choices={withCurrent(offered.map(source => ({ value: source.name, label: source.label })), value)} />
  );
}

interface NamedValueProps extends Common {
  operand: DraftOperand;
  onChange: (value: string[]) => void;
}

// What an operand's source reads, when it reads something it is told to
function NamedValue({ operand, sources, editors, disabled, onChange }: NamedValueProps) {
  const label = sourceOf(sources, operand.source)?.valueLabel;
  if (label === undefined) {
    return null;
  }
  const editor = editors[operand.source];
  if (editor) {
    return editor({ label, value: operand.value, disabled, onChange });
  }
  return (
    <TextField fullWidth label={label} value={operand.value.at(0) ?? ""} disabled={disabled}
      onChange={event => onChange(event.target.value === "" ? [] : [ event.target.value ])} />
  );
}

const withAggregate = ({ aggregate: _, ...operand }: DraftOperand, aggregate: string): DraftOperand =>
  (aggregate === "" ? operand : { ...operand, aggregate });

interface SingleEditorProps extends Common {
  condition: DraftSingle;
  onChange: (condition: DraftSingle) => void;
  onRemove: () => void;
}

function SingleEditor({ condition, onChange, onRemove, ...common }: SingleEditorProps) {
  const { sources, disabled } = common;
  const { a, b } = condition;
  const shape = shapeOf(a, sources);
  const aggregates = aggregatesFor(shapeOf({ ...a, aggregate: undefined }, sources));
  const offered = comparatorsFor(shape);
  const comparator = comparatorOf(condition.comparator);
  const taken = valuesTaken(comparator, shape);

  // Once what is compared changes, the values it is compared with start again
  const withA = (next: DraftOperand) => {
    const kept = comparatorsFor(shapeOf(next, sources)).some(item => item.name === condition.comparator)
      || comparator === undefined;
    onChange({ ...condition, a: next, comparator: kept ? condition.comparator : "equals",
      b: b.source === LITERAL ? newOperand() : b });
  };
  const withComparator = (name: string) => onChange({ ...condition, comparator: name,
    b: b.source === LITERAL && valuesTaken(comparatorOf(name), shape) === "one" ? { ...b, value: b.value.slice(0, 1) }
      : b });

  return (
    <Paper variant="outlined" role="group" aria-label="Condition"
      sx={{ display: "flex", gap: 1, alignItems: "flex-start", p: 2 }}>
      <Box sx={{ flex: 1, display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" } }}>
        <SourceSelect label="Compare" value={a.source} sources={sources} disabled={disabled}
          onChange={source => withA(newOperand(source))} />
        <NamedValue {...common} operand={a} onChange={value => withA({ ...a, value })} />
        { (aggregates.length > 0 || a.aggregate !== undefined) && (
          <ChoiceSelect label="Using" value={a.aggregate ?? ""} disabled={disabled}
            choices={withCurrent([ { value: "", label: "The values" },
              ...aggregates.map(item => ({ value: item.name, label: item.label })) ], a.aggregate)}
            onChange={value => withA(withAggregate(a, value))} />
        ) }
        <ChoiceSelect label="Comparison" value={condition.comparator} disabled={disabled}
          choices={withCurrent(offered.map(item => ({ value: item.name, label: item.phrase })), condition.comparator,
            name => comparatorOf(name)?.phrase ?? name)}
          onChange={withComparator} />
        { taken !== "none" && (
          <>
            <SourceSelect label="Compared with" literal value={b.source} sources={sources} disabled={disabled}
              onChange={source => onChange({ ...condition, b: newOperand(source) })} />
            { b.source === LITERAL
              ? (
                <OperandValueInput label={taken === "several" ? "Values" : "Value"}
                  shape={secondShapeOf(condition, sources)} several={taken === "several"} value={b.value}
                  disabled={disabled} onChange={value => onChange({ ...condition, b: { ...b, value } })} />
              )
              : (
                <NamedValue {...common} operand={b}
                  onChange={value => onChange({ ...condition, b: { ...b, value } })} />
              ) }
          </>
        ) }
      </Box>
      <IconButton aria-label="Remove this condition" disabled={disabled} onClick={onRemove}>
        <CloseOutlinedIcon />
      </IconButton>
    </Paper>
  );
}

interface GroupEditorProps extends Common {
  group: DraftGroup;
  onChange: (group: DraftGroup) => void;
  // Absent at the top, which cannot be removed
  onRemove?: () => void;
}

function GroupEditor({ group, onChange, onRemove, ...common }: GroupEditorProps) {
  const { sources, disabled } = common;
  const first = sources.at(0)?.name ?? LITERAL;
  const replace = (at: number, next?: DraftCondition) => onChange({ ...group,
    conditions: next ? group.conditions.map((condition, index) => (index === at ? next : condition))
      : group.conditions.filter((_condition, index) => index !== at) });
  const add = (condition: DraftCondition) => onChange({ ...group, conditions: [ ...group.conditions, condition ] });

  return (
    <Stack spacing={2} role="group" aria-label={onRemove ? "Group of conditions" : "Conditions"}
      sx={onRemove ? { borderLeft: 3, borderColor: "divider", pl: 2 } : undefined}>
      { group.conditions.length > 1 && (
        <Stack direction="row" sx={{ alignItems: "center", gap: 1, flexWrap: "wrap" }}>
          <ToggleButtonGroup size="small" exclusive value={group.requireAll ? "all" : "any"} disabled={disabled}
            aria-label="Which of these must hold"
            onChange={(_event, value: string | null) => {
              if (value !== null) {
                onChange({ ...group, requireAll: value === "all" });
              }
            }}>
            <ToggleButton value="all">All</ToggleButton>
            <ToggleButton value="any">Any</ToggleButton>
          </ToggleButtonGroup>
          <Typography>of these must hold.</Typography>
        </Stack>
      ) }
      { !onRemove && group.conditions.length === 0 && (
        <Typography variant="placeholder">No condition. It always applies.</Typography>
      ) }
      { group.conditions.map((condition, at) => {
        switch (condition.kind) {
          case "single":
            return <SingleEditor key={condition.id} {...common} condition={condition}
              onChange={next => replace(at, next)} onRemove={() => replace(at)} />;
          case "group":
            return <GroupEditor key={condition.id} {...common} group={condition}
              onChange={next => replace(at, next)} onRemove={() => replace(at)} />;
          case "unknown":
            return (
              <Stack key={condition.id} direction="row" sx={{ alignItems: "center", gap: 1 }}>
                <Typography variant="description" sx={{ flex: 1 }}>
                  This condition is of a kind this editor does not know. Remove it to save the others.
                </Typography>
                <IconButton aria-label="Remove this condition" disabled={disabled} onClick={() => replace(at)}>
                  <CloseOutlinedIcon />
                </IconButton>
              </Stack>
            );
        }
      }) }
      <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap" }}>
        <Button startIcon={<AddOutlinedIcon />} disabled={disabled} onClick={() => add(newSingle(first))}>
          Add a condition
        </Button>
        <Button startIcon={<AddOutlinedIcon />} disabled={disabled}
          onClick={() => add({ ...newGroup(), conditions: [ newSingle(first) ] })}>
          Add a group
        </Button>
        { onRemove && (
          <Button startIcon={<CloseOutlinedIcon />} disabled={disabled} onClick={onRemove} sx={{ ml: "auto" }}>
            Remove this group
          </Button>
        ) }
      </Stack>
    </Stack>
  );
}

interface ConditionBuilderProps {
  draft: DraftGroup;
  onChange: (draft: DraftGroup) => void;
  // Where the operands' values may come from, the first one offered first
  sources: OperandSource[];
  // How the operands of some sources name what they read, when not as text
  editors?: OperandEditors;
  disabled?: boolean;
}

// Builds a condition: groups of conditions of which all or any must hold, each comparing what a source reads with
// values of the type it holds, or with what another source reads
function ConditionBuilder({ draft, onChange, sources, editors = {}, disabled = false }: ConditionBuilderProps) {
  return <GroupEditor group={draft} onChange={onChange} sources={sources} editors={editors} disabled={disabled} />;
}

export default ConditionBuilder;
