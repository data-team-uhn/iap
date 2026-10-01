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

import { Fragment, type ReactNode, useState } from "react";

import AddOutlinedIcon from "@mui/icons-material/AddOutlined";
import CloseOutlinedIcon from "@mui/icons-material/CloseOutlined";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import KeyboardArrowRightIcon from "@mui/icons-material/KeyboardArrowRight";
import {
  Box, Button, Chip, IconButton, MenuItem, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from "@mui/material";

import {
  aggregatesFor, type Choice, choicesOf, comparatorOf, comparatorsFor, describeDraft, type DraftCondition,
  type DraftGroup, type DraftOperand, type DraftSingle, isChosen, isComplete, LITERAL, newGroup, newOperand,
  newSingle, type OperandSource, secondShapeOf, shapeOf, sourceOf, valuesTaken, withCurrent,
} from "./conditionModel";
import OperandValueInput from "./OperandValueInput";

// What a source's operand names, entered as the source offering it asks, e.g. a question picked from a list
export interface OperandEditorProps {
  label: string;
  value: string[];
  disabled: boolean;
  required: boolean;
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

const NARROW = { width: { xs: "100%", sm: "15rem" }, flexShrink: 0 };
const WIDE = { flex: 1, minWidth: 0, width: { xs: "100%", sm: "auto" } };

// The column where a group's conditions are joined
const RAIL = 48;

function Row({ children }: { children: ReactNode }) {
  return <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5}>{children}</Stack>;
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
    <TextField select required fullWidth label={label} value={value} disabled={disabled}
      onChange={event => onChange(event.target.value)}>
      { choices.map(choice => <MenuItem key={choice.value} value={choice.value}>{choice.label}</MenuItem>) }
    </TextField>
  );
}

interface SourceSelectProps {
  label: string;
  value: string;
  sources: OperandSource[];
  // How giving values of its own is offered, if the operand can
  literal?: string;
  disabled: boolean;
  onChange: (source: string) => void;
}

function SourceSelect({ label, value, sources, literal, disabled, onChange }: SourceSelectProps) {
  const offered = [ ...literal ? [ { name: LITERAL, label: literal } ] : [], ...sources ];
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
    return editor({ label, value: operand.value, disabled, required: true, onChange });
  }
  return (
    <TextField required fullWidth label={label} value={operand.value.at(0) ?? ""} disabled={disabled}
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
  const waiting = disabled || !isChosen(a, sources);
  // For the values' choices to open as soon as what is compared is chosen
  const [ justChosen, setJustChosen ] = useState(false);

  // What it is compared with starts again, as "Yes" for a yes or no
  const withA = (next: DraftOperand) => {
    const nextShape = shapeOf(next, sources);
    const kept = comparatorsFor(nextShape).some(item => item.name === condition.comparator)
      || comparator === undefined;
    setJustChosen(isChosen(next, sources) && choicesOf(nextShape).length > 0);
    onChange({ ...condition, a: next, comparator: kept ? condition.comparator : "equals",
      b: b.source === LITERAL ? { ...newOperand(), value: nextShape.type === "boolean" ? [ "true" ] : [] } : b });
  };
  const withComparator = (name: string) => {
    setJustChosen(false);
    onChange({ ...condition, comparator: name,
      b: b.source === LITERAL && valuesTaken(comparatorOf(name), shape) === "one"
        ? { ...b, value: b.value.slice(0, 1) } : b });
  };

  return (
    <Box role="group" aria-label="Condition"
      sx={{ display: "flex", gap: 1, alignItems: "flex-start", p: 2, borderRadius: 1, bgcolor: "background.muted" }}>
      <Stack spacing={2.5} sx={{ flex: 1, minWidth: 0 }}>
        <Row>
          <Box sx={NARROW}>
            <SourceSelect label="Compare" value={a.source} sources={sources} disabled={disabled}
              onChange={source => withA(newOperand(source))} />
          </Box>
          { sourceOf(sources, a.source)?.valueLabel !== undefined && (
            <Box sx={WIDE}>
              <NamedValue {...common} operand={a} onChange={value => withA({ ...a, value })} />
            </Box>
          ) }
          { (aggregates.length > 0 || a.aggregate !== undefined) && (
            <Box sx={NARROW}>
              <ChoiceSelect label="Using" value={a.aggregate ?? ""} disabled={disabled}
                choices={withCurrent([ { value: "", label: "The values" },
                  ...aggregates.map(item => ({ value: item.name, label: item.label })) ], a.aggregate)}
                onChange={value => withA(withAggregate(a, value))} />
            </Box>
          ) }
        </Row>
        <Row>
          <Box sx={NARROW}>
            <ChoiceSelect label="Comparison" value={condition.comparator} disabled={disabled}
              choices={withCurrent(offered.map(item => ({ value: item.name, label: item.phrase })),
                condition.comparator, name => comparatorOf(name)?.phrase ?? name)}
              onChange={withComparator} />
          </Box>
        </Row>
        { taken !== "none" && (
          <Row>
            <Box sx={NARROW}>
              <SourceSelect label="Compared with" value={b.source} sources={sources} disabled={disabled}
                literal={taken === "several" ? "Specific values" : "A specific value"}
                onChange={source => onChange({ ...condition, b: newOperand(source) })} />
            </Box>
            <Box sx={WIDE}>
              { b.source === LITERAL
                ? (
                  <OperandValueInput key={a.value.at(0)} label={taken === "several" ? "Values" : "Value"} required
                    open={justChosen}
                    shape={secondShapeOf(condition, sources)} several={taken === "several"} value={b.value}
                    disabled={waiting} onChange={value => {
                      setJustChosen(false);
                      onChange({ ...condition, b: { ...b, value } });
                    }} />
                )
                : (
                  <NamedValue {...common} disabled={waiting} operand={b}
                    onChange={value => onChange({ ...condition, b: { ...b, value } })} />
                ) }
            </Box>
          </Row>
        ) }
      </Stack>
      <IconButton aria-label="Remove this condition" disabled={disabled} onClick={onRemove}>
        <CloseOutlinedIcon />
      </IconButton>
    </Box>
  );
}

interface GroupEditorProps extends Common {
  group: DraftGroup;
  // How deep it is, the top being 0
  depth: number;
  onChange: (group: DraftGroup) => void;
  // Absent at the top, which cannot be removed
  onRemove?: () => void;
}

function summaryOf(group: DraftGroup, sources: OperandSource[]): string {
  if (group.conditions.length === 0) {
    return "No condition yet.";
  }
  return isComplete(group, sources) ? describeDraft(group, sources) : "Some of these are not complete yet.";
}

function GroupEditor({ group, depth, onChange, onRemove, ...common }: GroupEditorProps) {
  const { sources, disabled } = common;
  const [ open, setOpen ] = useState(true);
  const first = sources.at(0)?.name ?? LITERAL;
  const nested = depth > 0;
  const replace = (at: number, next?: DraftCondition) => onChange({ ...group,
    conditions: next ? group.conditions.map((condition, index) => (index === at ? next : condition))
      : group.conditions.filter((_condition, index) => index !== at) });
  const add = (condition: DraftCondition) => onChange({ ...group, conditions: [ ...group.conditions, condition ] });
  const joiner = group.requireAll ? "And" : "Or";
  // Joined along a line, once there is an All or Any to say how
  const railed = nested || group.conditions.length > 1;

  return (
    <Stack spacing={1.5} role="group" aria-label={nested ? "Group of conditions" : "Conditions"}
      // Translucent, so that each level is shaded deeper than the one it is in
      sx={nested ? { bgcolor: "background.muted", borderRadius: 1, p: 1 } : undefined}>
      { (nested || group.conditions.length > 1) && (
        <Stack direction="row" sx={{ alignItems: "center", columnGap: 1, rowGap: 1, flexWrap: "wrap" }}>
          <Box sx={{ width: RAIL, mr: -1, flexShrink: 0, display: "flex", justifyContent: "center" }}>
            <IconButton size="small" aria-label={open ? "Collapse this group" : "Expand this group"}
              aria-expanded={open} onClick={() => setOpen(current => !current)}>
              { open ? <ExpandMoreIcon fontSize="small" /> : <KeyboardArrowRightIcon fontSize="small" /> }
            </IconButton>
          </Box>
          <ToggleButtonGroup size="small" color="primary" exclusive value={group.requireAll ? "all" : "any"}
            disabled={disabled}
            aria-label="Which of these must hold"
            onChange={(_event, value: string | null) => {
              if (value !== null) {
                onChange({ ...group, requireAll: value === "all" });
              }
            }}>
            <ToggleButton value="all">All</ToggleButton>
            <ToggleButton value="any">Any</ToggleButton>
          </ToggleButtonGroup>
          <Typography>of these are true</Typography>
          { onRemove && (
            <Button variant="text" disabled={disabled} onClick={onRemove} sx={{ ml: "auto" }}>
              Remove this group
            </Button>
          ) }
        </Stack>
      ) }
      {/* At the top, what the dialog says already reads a complete condition */}
      { !open && (nested || !isComplete(group, sources)) && (
        <Typography variant="description" sx={{ px: 1 }}>{summaryOf(group, sources)}</Typography>
      ) }
      { open && !nested && group.conditions.length === 0 && (
        <Typography variant="placeholder">No condition. It always applies.</Typography>
      ) }
      { open && (
        <Box sx={railed ? { position: "relative", pl: `${RAIL}px` } : undefined}>
          { railed && (
            <Box aria-hidden sx={{ position: "absolute", left: RAIL / 2 - 1, top: 0, bottom: 0, width: 2,
              borderRadius: 1, bgcolor: "background.tintedStrong" }} />
          ) }
          <Stack spacing={1.5}>
            { group.conditions.map((condition, at) => (
              <Fragment key={condition.id}>
                { at > 0 && (
                  <Box sx={{ position: "relative", height: 24 }}>
                    <Chip label={joiner} size="small" variant="outlined" color="primary"
                      sx={{ position: "absolute", left: -RAIL / 2, translate: "-50%", bgcolor: "background.paper" }} />
                  </Box>
                ) }
                { condition.kind === "single" && (
                  <SingleEditor {...common} condition={condition} onChange={next => replace(at, next)}
                    onRemove={() => replace(at)} />
                ) }
                { condition.kind === "group" && (
                  <GroupEditor {...common} group={condition} depth={depth + 1} onChange={next => replace(at, next)}
                    onRemove={() => replace(at)} />
                ) }
                { condition.kind === "unknown" && (
                  <Stack direction="row" sx={{ alignItems: "center", gap: 1 }}>
                    <Typography variant="description" sx={{ flex: 1 }}>
                      This condition is of a kind this editor does not know. Remove it to save the others.
                    </Typography>
                    <IconButton aria-label="Remove this condition" disabled={disabled} onClick={() => replace(at)}>
                      <CloseOutlinedIcon />
                    </IconButton>
                  </Stack>
                ) }
              </Fragment>
            )) }
            <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap" }}>
              <Button variant="text" startIcon={<AddOutlinedIcon />} disabled={disabled} aria-label="Add a condition"
                onClick={() => add(newSingle(first))}>
                Condition
              </Button>
              <Button variant="text" startIcon={<AddOutlinedIcon />} disabled={disabled} aria-label="Add a group"
                onClick={() => add({ ...newGroup(), conditions: [ newSingle(first) ] })}>
                Group
              </Button>
            </Stack>
          </Stack>
        </Box>
      ) }
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
  return (
    <GroupEditor group={draft} depth={0} onChange={onChange} sources={sources} editors={editors}
      disabled={disabled} />
  );
}

export default ConditionBuilder;
