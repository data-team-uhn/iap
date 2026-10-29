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

import { TextField } from "@mui/material";

import { commitProps, fromSingle, singleValue, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

interface TextInputProps extends ValueInputProps {
  multiline?: boolean;
}

// One value, typed freely.
export default function TextInput(props: TextInputProps) {
  const { label, values, onChange, onCommit, multiline = false, readOnly = false } = props;
  const { field, visit } = useFieldProps(props);

  if (readOnly) {
    return <ValueText label={label} values={values} />;
  }
  const commit = () => {
    visit();
    onCommit?.(values);
  };
  return (
    <TextField
      {...field}
      value={singleValue(values)}
      multiline={multiline}
      minRows={multiline ? 2 : undefined}
      onChange={event => onChange(fromSingle(event.target.value))}
      // Enter in a multiline field starts a new line
      {...multiline ? { onBlur: commit } : commitProps(commit)}
    />
  );
}
