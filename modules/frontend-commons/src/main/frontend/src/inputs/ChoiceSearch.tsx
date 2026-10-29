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

import { Autocomplete, TextField } from "@mui/material";

import { choiceLabel, withCurrent } from "./choices";
import { type ChoiceSource, useChoices } from "./choiceSource";
import MultiValueInput from "./MultiValueInput";
import { fromSingle, picker, useFieldProps, type ValueInputProps } from "./valueInput";
import ValueText from "./ValueText";

interface ChoiceSearchProps extends ValueInputProps {
  source: ChoiceSource;
  multiple?: boolean;
  // Whether a value's chip is marked in error
  chipError?: (value: string) => boolean;
}

// One value or several, picked from a list that narrows as it is typed.
export default function ChoiceSearch(props: ChoiceSearchProps) {
  const {
    label,
    values,
    source,
    multiple = false,
    chipError,
    problems = [],
    disabled,
    readOnly = false
  } = props;
  const { choices, loading, loadError } = useChoices(source);
  const shown = { ...props, problems: loadError ? [ ...problems, loadError ] : problems };
  const { field, visit } = useFieldProps(shown);
  // Keep stored values even if they aren't in the standard choices
  const known = values.reduce((list, value) => withCurrent(list, value, source.labelOf), choices);

  if (readOnly) {
    return <ValueText label={label} values={values} choices={known} />;
  }
  if (multiple) {
    return <MultiValueInput
      {...shown}
      choices={known}
      free={false}
      loading={loading}
      chipError={chipError}
    />;
  }
  return (
    <Autocomplete<string>
      options={known.map(choice => choice.value)}
      getOptionLabel={value => choiceLabel(known, value)}
      value={values.at(0) ?? null}
      loading={loading}
      disabled={disabled}
      onChange={(_event, picked) => picker(props)(fromSingle(picked ?? ""))}
      onBlur={visit}
      renderInput={params => (
        <TextField
          {...params}
          label={field.label}
          required={field.required}
          error={field.error}
          helperText={field.helperText}
        />
      )}
    />
  );
}
