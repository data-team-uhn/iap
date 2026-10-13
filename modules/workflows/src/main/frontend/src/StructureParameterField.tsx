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

import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import DeleteIcon from "@mui/icons-material/Delete";
import {
  Button,
  IconButton,
  Paper,
  Stack,
  Typography,
} from "@mui/material";

import { picker, useVisited } from "@iap/frontend-commons/inputs/valueInput";
import ValueText from "@iap/frontend-commons/inputs/ValueText";

import ParameterField from "./ParameterField";
import { valuesOf } from "./parameterValues";
import { valueProblems } from "./parameterWidget";
import { fieldsOf, isMultiple } from "./workflowTypes";

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
  const withField = (field: Parameter, values: ParameterValue[]) => ({ ...record, [field.name]: values });
  return (
    <Paper variant="outlined" sx={{ p: 2 }} role="group" aria-label={title}>
      <Stack spacing={2}>
        <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between" }}>
          <Typography variant="subtitle2">{title}</Typography>
          {actions}
        </Stack>
        { problem && <Typography color="error" variant="body2">{problem}</Typography> }
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

// A parameter holding fields of its own. A single one is a group of fields. A repeated one is a list of
// records, each titled by its key, that can be added, removed and reordered.
export default function StructureParameterField(props: ParameterFieldProps<StructureRecord>) {
  const { parameter, values, onChange, onCommit, readOnly = false } = props;
  // Visited once focus leaves anything inside it, a field or a button
  const [ visited, visit ] = useVisited();
  // A read-only panel flags nothing, as it shows every other field as text
  const problem = readOnly ? undefined : valueProblems(parameter, values, visited).at(0);

  // Reordering and removing are finished the moment they are made
  const pick = picker(props);

  if (!isMultiple(parameter)) {
    return (
      <div onBlur={visit}>
        <StructureRecordGroup
          parameter={parameter}
          record={values.at(0) ?? {}}
          title={parameter.label}
          problem={problem}
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

  const replaced = (index: number, record: StructureRecord) =>
    values.map((held, at) => at === index ? record : held);
  const moved = (index: number, to: number) => {
    const next = [ ...values ];
    [ next[index], next[to] ] = [ next[to], next[index] ];
    return next;
  };
  const keyOf = (record: StructureRecord) => {
    const key = parameter.keyParameter === undefined ? undefined : valuesOf(record, parameter.keyParameter).at(0);
    return typeof key === "string" ? key : "";
  };
  const titleOf = (record: StructureRecord) => keyOf(record).trim() === "" ? `New ${parameter.label}` : keyOf(record);
  // The server keeps the first record of a key, comparing keys trimmed, and drops any later one
  const keys = values.map(record => keyOf(record).trim());
  const repeatedKey = (index: number) => !readOnly && keys[index] !== "" && keys.indexOf(keys[index]) < index
    ? `Another ${parameter.label} has the key "${keys[index]}", so this one will be ignored`
    : undefined;
  const full = parameter.maxValues > 0 && values.length >= parameter.maxValues;

  return (
    <Stack spacing={1} onBlur={visit}>
      <Typography>{parameter.label}</Typography>
      {
        parameter.description && <Typography variant="description">{parameter.description}</Typography>
      }
      { problem && <Typography color="error" variant="body2">{problem}</Typography> }
      {
        values.map((record, index) => {
          const title = titleOf(record);
          return (
            <StructureRecordGroup
              // The position, since the key is what is being typed
              key={index}
              parameter={parameter}
              record={record}
              title={title}
              problem={repeatedKey(index)}
              readOnly={readOnly}
              onChange={changed => onChange(replaced(index, changed))}
              onCommit={changed => onCommit(replaced(index, changed))}
              actions={!readOnly && (
                <Stack direction="row">
                  <IconButton
                    aria-label={`Move ${title} up`}
                    disabled={index === 0}
                    onClick={() => pick(moved(index, index - 1))}
                    size="small"
                  >
                    <ArrowUpwardIcon fontSize="small" />
                  </IconButton>
                  <IconButton
                    aria-label={`Move ${title} down`}
                    disabled={index === values.length - 1}
                    onClick={() => pick(moved(index, index + 1))}
                    size="small"
                  >
                    <ArrowDownwardIcon fontSize="small" />
                  </IconButton>
                  <IconButton
                    aria-label={`Remove ${title}`}
                    onClick={() => pick(values.filter((_held, at) => at !== index))}
                    size="small"
                  >
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Stack>
              )}
            />
          );
        })
      }
      {
        // A record added is a draft until something is typed into it
        !readOnly && <Button disabled={full} onClick={() => onChange([ ...values, {} ])}>Add {parameter.label}</Button>
      }
    </Stack>
  );
}
