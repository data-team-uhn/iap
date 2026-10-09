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

import { useState } from "react";

import KeyboardArrowDownIcon from "@mui/icons-material/KeyboardArrowDown";
import KeyboardArrowUpIcon from "@mui/icons-material/KeyboardArrowUp";
import UnfoldMoreIcon from "@mui/icons-material/UnfoldMore";
import { Box, ButtonBase, Chip, Stack, Typography } from "@mui/material";

import CodePill from "@iap/frontend-commons/components/CodePill";
import { compareText } from "@iap/frontend-commons/diff/contentDiffModel";
import TextDiff from "@iap/frontend-commons/diff/TextDiff";
import ValueChange from "@iap/frontend-commons/diff/ValueChange";
import { TOUCH_TARGET } from "@iap/frontend-commons/touchTarget";

import { type ComparedField, isTextChange, shownValue } from "./comparisonFields";
import {
  type Change, type LabelledDifference, type OptionComparison, type PartComparison,
} from "./schemaComparisonModel";
import { schemaPartTypeOf } from "./schemaPartTypes";

// The theme's colours for what was added or removed, else for what moved, nothing for the rest
const colorsOf = (change: Change, moved = false) => {
  if (change === "added" || change === "removed") {
    return `diff.${change}`;
  }
  return moved ? "diff.moved" : undefined;
};

const MOVED_CHIP = { color: "diff.moved.main", borderColor: "diff.moved.main" };

const CHANGE_WORDS: Record<Change, string | undefined> = {
  added: "Added", removed: "Removed", changed: "Changed", unchanged: undefined,
};

// A text field's value, none when it has none
const textOf = (value: unknown): string => (typeof value === "string" ? value : "");

// What tells a part apart from its siblings: a removed part and an added one may share a name, if not a type
const keyOf = (part: PartComparison) => `${part.change} ${part.type} ${part.name}`;

// Whether anything happened to a part or anything in it
const touched = (part: PartComparison): boolean => part.change !== "unchanged" || part.movedFrom !== undefined
  || part.reordered || part.parts.some(touched);

// What happened to a part or an option, in words, and so not by colour alone
function ChangeChips({ change, movedFrom, reordered }: {
  change: Change;
  movedFrom?: string | null;
  reordered: boolean;
}) {
  const colors = colorsOf(change);
  const word = CHANGE_WORDS[change];
  return (
    <>
      { word && (
        <Chip size="small" variant="outlined" label={word}
          sx={colors ? { color: `${colors}.main`, borderColor: `${colors}.main` } : undefined} />
      ) }
      { movedFrom !== undefined && (
        <Chip size="small" variant="outlined" sx={MOVED_CHIP}
          label={movedFrom === null ? "Moved from the top level" : `Moved from “${movedFrom}”`} />
      ) }
      { reordered && <Chip size="small" variant="outlined" label="Order changed" sx={MOVED_CHIP} /> }
    </>
  );
}

interface Described {
  fields: ComparedField[];
  // What references point at, by their paths and identifiers
  names: Record<string, string>;
}

// The fields that changed, texts word by word and other values as what they were and are
export function FieldChanges({ differences, fields, names }: { differences: LabelledDifference[] } & Described) {
  if (differences.length === 0) {
    return null;
  }
  return (
    <Stack spacing={1}>
      { differences.map(difference => {
        const field = fields.find(candidate => candidate.name === difference.field);
        const { before, after } = difference;
        return (
          <Box key={difference.field}>
            <Typography variant="caption" sx={{ display: "block" }}>{difference.label}</Typography>
            { isTextChange(field, before, after)
              ? <TextDiff lines={compareText(textOf(before), textOf(after))} />
              : (
                <ValueChange before={before === undefined ? undefined : shownValue(field, before, names)}
                  after={after === undefined ? undefined : shownValue(field, after, names)} />
              ) }
          </Box>
        );
      }) }
    </Stack>
  );
}

// A question's options that changed, and how many did not
function OptionChanges({ options, fields, names }: { options: OptionComparison[] } & Described) {
  const changed = options.filter(option => option.change !== "unchanged" || option.reordered);
  if (changed.length === 0) {
    return null;
  }
  const unchanged = options.length - changed.length;
  return (
    <Box>
      <Typography variant="caption" sx={{ display: "block" }}>Options</Typography>
      <Stack component="ul" spacing={1} sx={{ m: 0, p: 0 }}>
        { changed.map(option => {
          const colors = colorsOf(option.change, option.reordered);
          return (
            <Box component="li" key={option.name} sx={{
              listStyle: "none", px: 1, py: 0.5, borderRadius: 1, bgcolor: colors ? `${colors}.line` : undefined,
            }}>
              <Stack direction="row" spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
                <Typography variant="body2">{option.label}</Typography>
                <CodePill name={option.name} />
                <ChangeChips change={option.change} reordered={option.reordered} />
              </Stack>
              <FieldChanges differences={option.fields} fields={fields} names={names} />
            </Box>
          );
        }) }
      </Stack>
      { unchanged > 0 && (
        <Typography variant="description">
          { unchanged === 1 ? "1 other option is the same." : `${unchanged} other options are the same.` }
        </Typography>
      ) }
    </Box>
  );
}

interface PartListProps {
  parts: PartComparison[];
  // The fields of parts, then of options
  fields: { part: ComparedField[]; option: ComparedField[] };
  names: Record<string, string>;
  // Whether parts where nothing happened are shown too, rather than counted
  everything: boolean;
}

// Which way unchanged parts left out open, as the diffs people know show it: before what follows them at the start of
// a list, after what precedes them at its end, and both ways between two changes
const UNFOLDING = { start: KeyboardArrowUpIcon, end: KeyboardArrowDownIcon, between: UnfoldMoreIcon };

// Parts where nothing happened, counted in a band that shows them when pressed
function UnchangedRun({ parts, fields, names, at }: Omit<PartListProps, "everything"> & {
  at: keyof typeof UNFOLDING;
}) {
  const [ shown, setShown ] = useState(false);
  if (shown) {
    return parts.map(part => <ComparedPart key={keyOf(part)} part={part} fields={fields} names={names} everything />);
  }
  const kinds = new Set(parts.map(part => schemaPartTypeOf(part.type).label.toLowerCase()));
  const kind = kinds.size === 1 ? [ ...kinds ][0] : "part";
  const Icon = UNFOLDING[at];
  return (
    <Box component="li" sx={{ listStyle: "none", mb: 1 }}>
      <ButtonBase onClick={() => setShown(true)} sx={{
        width: "100%", justifyContent: "flex-start", gap: 1, px: 1, py: 0.75, border: 1, borderColor: "transparent",
        borderRadius: 1, bgcolor: "diff.hunk.line", color: "diff.hunk.main", typography: "body2", textAlign: "start",
        "&:hover, &.Mui-focusVisible": { borderColor: "diff.hunk.main" }, ...TOUCH_TARGET,
      }}>
        <Icon fontSize="small" />
        { parts.length === 1 ? `Show 1 unchanged ${kind}` : `Show ${parts.length} unchanged ${kind}s` }
      </ButtonBase>
    </Box>
  );
}

// Where a run stands in its list: first or last of several, or else between changes
const placeOf = (index: number, count: number): keyof typeof UNFOLDING => {
  if (count > 1 && index === 0) {
    return "start";
  }
  return count > 1 && index === count - 1 ? "end" : "between";
};

// Parts one under the other, those where nothing happened gathered in runs
function PartList({ parts, fields, names, everything }: PartListProps) {
  const runs = parts.reduce<PartComparison[][]>((grouped, part) => {
    const last = grouped.at(-1);
    if (!everything && !touched(part) && last && !touched(last[0])) {
      last.push(part);
    } else {
      grouped.push([ part ]);
    }
    return grouped;
  }, []);
  return (
    <Box component="ul" sx={{ m: 0, p: 0 }}>
      { runs.map((run, index) => (!everything && !touched(run[0])
        ? (
          <UnchangedRun key={keyOf(run[0])} parts={run} fields={fields} names={names}
            at={placeOf(index, runs.length)} />
        )
        : <ComparedPart key={keyOf(run[0])} part={run[0]} fields={fields} names={names} everything={everything} />)) }
    </Box>
  );
}

// One part as two versions have it: what happened to it, its fields that changed, when it applies, its options, and
// what it holds
function ComparedPart({ part, fields, names, everything }: { part: PartComparison } & Omit<PartListProps, "parts">) {
  const type = schemaPartTypeOf(part.type);
  const colors = colorsOf(part.change, part.movedFrom !== undefined || part.reordered);
  const { Icon } = type;
  return (
    <Box component="li" sx={{
      listStyle: "none", mb: 1, p: 1, border: 1, borderColor: "divider", borderLeft: 4, borderRadius: 1,
      borderLeftColor: colors ? `${colors}.main` : type.accent, bgcolor: colors ? `${colors}.line` : "background.paper",
    }}>
      <Stack spacing={1}>
        <Stack direction="row" spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
          {/* The icon at the start of the heading, which wraps under it */}
          <Typography sx={{ fontWeight: type.weight, overflowWrap: "anywhere" }}>
            <Icon fontSize="small" titleAccess={type.label}
              sx={{ color: type.accent, verticalAlign: "text-bottom", mr: 0.75 }} />
            {part.heading}
          </Typography>
          <CodePill name={part.name} />
          <ChangeChips change={part.change} movedFrom={part.movedFrom} reordered={part.reordered} />
        </Stack>
        <FieldChanges differences={part.fields} fields={fields.part} names={names} />
        { part.condition && (
          <Box>
            <Typography variant="caption" sx={{ display: "block" }}>When it applies</Typography>
            { part.condition.before === undefined && part.condition.after === undefined
              ? <Typography variant="description">The condition changed in a way its words cannot show.</Typography>
              : <TextDiff lines={compareText(part.condition.before ?? "", part.condition.after ?? "")} /> }
          </Box>
        ) }
        <OptionChanges options={part.options} fields={fields.option} names={names} />
        { part.parts.length > 0 && (
          <Box sx={{ pl: { xs: 1, sm: 3 } }}>
            <PartList parts={part.parts} fields={fields} names={names} everything={everything} />
          </Box>
        ) }
      </Stack>
    </Box>
  );
}

export default PartList;
