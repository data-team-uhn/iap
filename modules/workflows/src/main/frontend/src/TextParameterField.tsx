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
  Autocomplete,
  Chip,
  MenuItem,
  TextField,
} from "@mui/material";

import { fromSingle, refusal, singleValue, splitList } from "./parameterValues";
import {
  baseFieldProps,
  commitProps,
  noneItem,
  optionLabel,
  ParameterText,
  picker,
  useVisited,
  valueProblems,
} from "./parameterWidget";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";
import type { Parameter } from "./workflowTypes";

// What is wrong with how many values a list holds, as the server would report it. An empty
// list of a required parameter is reported as missing a value instead.
function countProblems(parameter: Parameter, values: string[]): string[] {
  if (values.length > 0 && values.length < parameter.minValues) {
    return [ `At least ${parameter.minValues} values are needed` ];
  }
  if (parameter.maxValues > 0 && values.length > parameter.maxValues) {
    return [ `At most ${parameter.maxValues} values are accepted` ];
  }
  return [];
}

// A parameter edited as text: typed freely, or picked from the values its entry offers. This is also
// the field for any type that has none of its own.
export default function TextParameterField(props: ParameterFieldProps) {
  const { parameter, values, onChange, onCommit, commaSeparated = false, readOnly = false } = props;
  const [ visited, visit ] = useVisited();
  const single = singleValue(values);
  const constrained = parameter.options.length > 0;

  const pick = picker(props);

  if (readOnly) {
    return <ParameterText parameter={parameter} shown={values.map(value => optionLabel(parameter, value))} />;
  }

  // Several values are always picked or typed as chips, since a list has no natural single control.
  // They are free-form when the parameter constrains nothing, and a closed list when it does.
  if (isMultiple(parameter)) {
    const base = baseFieldProps(parameter,
      [ ...valueProblems(parameter, values, visited), ...countProblems(parameter, values) ]);
    return (
      <Autocomplete
        multiple
        freeSolo={!constrained}
        // Text typed and left without Enter is still a value. A closed list keeps only what was picked.
        autoSelect={!constrained}
        options={parameter.options.map(option => option.value)}
        getOptionLabel={value => optionLabel(parameter, value)}
        value={values}
        onChange={(_event, next) => pick(commaSeparated ? next.flatMap(splitList) : next)}
        onBlur={visit}
        renderValue={(selected, getItemProps) => selected.map((value, index) => {
          const { key, ...itemProps } = getItemProps({ index });
          return (
            <Chip
              key={key}
              label={optionLabel(parameter, value)}
              size="small"
              color={refusal(parameter, value) === undefined ? "default" : "error"}
              {...itemProps}
            />
          );
        })}
        renderInput={parameters => (
          <TextField
            {...parameters}
            label={base.label}
            required={base.required}
            error={base.error}
            helperText={base.helperText}
          />
        )}
      />
    );
  }

  const base = baseFieldProps(parameter, valueProblems(parameter, values, visited));
  return (
    <TextField
      select={constrained}
      {...base}
      value={single}
      onChange={event => (constrained ? pick : onChange)(fromSingle(event.target.value))}
      {...commitProps(() => {
        visit();
        onCommit(values);
      })}
    >
      { constrained && !base.required && noneItem() }
      {
        parameter.options.map(option => (
          <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
        ))
      }
      {
        // A stored value the entry no longer offers stays selected and visible, rather than being
        // silently dropped by a select that cannot show it
        constrained && single !== "" && !parameter.options.some(option => option.value === single)
          && <MenuItem value={single}>{single}</MenuItem>
      }
    </TextField>
  );
}
