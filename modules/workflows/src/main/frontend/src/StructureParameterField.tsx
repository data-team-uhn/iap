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

import { type ReactNode, useId, useState } from "react";

import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import {
  Alert,
  Button,
  Paper,
  Stack,
  Typography,
} from "@mui/material";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { useVisited } from "@iap/frontend-commons/inputs/valueInput";
import { changeAndCommit, countProblems } from "@iap/frontend-commons/inputs/values";
import ValueText from "@iap/frontend-commons/inputs/ValueText";

import ParameterField from "./ParameterField";
import { settled, valuesOf } from "./parameterValues";
import { countLimits, valueProblems } from "./parameterWidget";
import { keyOf, keyProblems } from "./structureKeys";
import { fieldsOf, hasSingleValuedKey, isMultiple } from "./workflowTypes";

import type { ParameterValue, StructureRecord } from "./parameterValues";
import type { ParameterFieldProps } from "./parameterWidget";
import type { Parameter } from "./workflowTypes";

interface RecordProps {
  parameter: Parameter;
  record: StructureRecord;
  title: string;
  problem?: string;
  onChange: (record: StructureRecord) => void;
  onCommit: (record: StructureRecord) => void;
  readOnly: boolean;
  actions?: ReactNode;
}

// One element of a structure: its fields, each in the field for its own type.
function StructureRecordGroup(props: RecordProps) {
  const { parameter, record, title, problem, onChange, onCommit, readOnly, actions } = props;
  const problemId = useId();
  const withField = (field: Parameter, values: ParameterValue[]) => ({ ...record, [field.name]: values });
  return (
    <Paper variant="outlined" sx={{ p: 2 }} role="group" aria-label={title}
      aria-describedby={problem ? problemId : undefined}>
      <Stack spacing={2}>
        <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between" }}>
          <Typography variant="subtitle2">{title}</Typography>
          {actions}
        </Stack>
        { problem && <Typography id={problemId} color="error" variant="body2">{problem}</Typography> }
        {
          fieldsOf(parameter).map(field => (
            <ParameterField
              key={field.name}
              parameter={field}
              values={valuesOf(record, field.name)}
              readOnly={readOnly}
              onChange={values => onChange(withField(field, values))}
              onCommit={values => onCommit(withField(field, values))}
            />
          ))
        }
      </Stack>
    </Paper>
  );
}

// A stable identity for each record, so that a record moved or removed takes its fields' state and the focus
// with it. A change made elsewhere, such as an undo, may change how many records there are: the records past
// those already known are given new ones.
function useRecordKeys(count: number) {
  const [ keys, setKeys ] = useState<string[]>(() => Array.from({ length: count }, () => crypto.randomUUID()));
  if (keys.length !== count) {
    setKeys(held => [
      ...held.slice(0, count),
      ...Array.from({ length: Math.max(0, count - held.length) }, () => crypto.randomUUID()),
    ]);
  }
  return [ keys, setKeys ] as const;
}

// The list with two of its items swapped.
function swapped<T>(items: readonly T[], index: number, to: number): T[] {
  const next = [ ...items ];
  [ next[index], next[to] ] = [ next[to], next[index] ];
  return next;
}

const without = <T,>(items: readonly T[], index: number): T[] => items.filter((_held, at) => at !== index);

// A parameter holding fields of its own. A single one is a group of fields. A repeated one is a list of
// records, each titled by its key, that can be added, removed and reordered.
export default function StructureParameterField(props: ParameterFieldProps<StructureRecord>) {
  const { parameter, values, onChange, onCommit, readOnly = false } = props;
  // Visited once focus leaves anything inside it, a field or a button
  const [ visited, visit ] = useVisited();
  const [ keys, setKeys ] = useRecordKeys(values.length);
  const problemId = useId();

  // Reordering and removing are finished the moment they are made
  const pick = changeAndCommit(props);

  if (!isMultiple(parameter)) {
    return (
      <div onBlur={visit}>
        <StructureRecordGroup
          parameter={parameter}
          record={values.at(0) ?? {}}
          title={parameter.label}
          // A read-only panel flags nothing
          problem={readOnly ? undefined : valueProblems(parameter, values, visited).at(0)}
          readOnly={readOnly}
          onChange={record => onChange([ record ])}
          onCommit={record => onCommit([ record ])}
        />
      </div>
    );
  }

  if (readOnly && values.length === 0) {
    return <ValueText label={parameter.label} values={[]} />;
  }

  // Without a key the server stores none of the records, so there is nothing an author could fill in usefully
  const keyless = !hasSingleValuedKey(parameter);
  const locked = readOnly || keyless;
  const { minValues, maxValues } = countLimits(parameter);
  const problem = locked ? undefined : [
    ...valueProblems(parameter, values, visited),
    ...countProblems(settled(values).length, minValues, maxValues),
  ].at(0);

  const replaced = (index: number, record: StructureRecord) =>
    values.map((held, at) => at === index ? record : held);
  const move = (index: number, to: number) => {
    setKeys(swapped(keys, index, to));
    pick(swapped(values, index, to));
  };
  const remove = (index: number) => {
    setKeys(without(keys, index));
    pick(without(values, index));
  };
  const titleOf = (record: StructureRecord) => {
    const key = keyOf(parameter, record);
    return key === "" ? `New ${parameter.label}` : key;
  };
  // A blank key stays with the key field's own "required"
  const keyProblem = keyProblems(parameter, values);
  const repeatedKey = (index: number) => !locked && keyProblem[index] === "repeated"
    ? `The key "${keyOf(parameter, values[index])}" is already in use, so this ${parameter.label} will be ignored.`
    : undefined;
  const full = maxValues !== undefined && values.length >= maxValues;

  return (
    <Stack spacing={1} onBlur={visit} role="group" aria-label={parameter.label}
      aria-describedby={problem ? problemId : undefined}>
      <Typography>{parameter.label}</Typography>
      {
        parameter.description && <Typography variant="description">{parameter.description}</Typography>
      }
      {
        keyless && !readOnly && (
          <Alert severity="warning">
            The node types declare no single-valued key for {parameter.label}, so the server ignores it.
          </Alert>
        )
      }
      { problem && <Typography id={problemId} color="error" variant="body2">{problem}</Typography> }
      {
        values.map((record, index) => {
          const title = titleOf(record);
          return (
            <StructureRecordGroup
              key={keys[index]}
              parameter={parameter}
              record={record}
              title={title}
              problem={repeatedKey(index)}
              readOnly={locked}
              onChange={changed => onChange(replaced(index, changed))}
              onCommit={changed => onCommit(replaced(index, changed))}
              actions={!locked && (
                <Stack direction="row">
                  <ActionIcon
                    label={`Move ${title} up`}
                    icon={<ArrowUpwardIcon fontSize="small" />}
                    disabled={index === 0}
                    onClick={() => move(index, index - 1)}
                  />
                  <ActionIcon
                    label={`Move ${title} down`}
                    icon={<ArrowDownwardIcon fontSize="small" />}
                    disabled={index === values.length - 1}
                    onClick={() => move(index, index + 1)}
                  />
                  <ActionIcon
                    label={`Remove ${title}`}
                    icon={<DeleteOutlinedIcon fontSize="small" />}
                    color="error"
                    onClick={() => remove(index)}
                  />
                </Stack>
              )}
            />
          );
        })
      }
      {
        // A record added is a draft until something is typed into it
        !locked && <Button disabled={full} onClick={() => onChange([ ...values, {} ])}>Add {parameter.label}</Button>
      }
    </Stack>
  );
}
