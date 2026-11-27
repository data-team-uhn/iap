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

// Lets a person pick from a long list, such as the nodes a reference may point at, by typing part of a label.

import { useMemo, useRef } from "react";

import { Autocomplete, TextField } from "@mui/material";

import { type Choice } from "./choices";
import { type ChoiceSource } from "./choiceSource";
import MultiValueInput from "./MultiValueInput";
import useChoices from "./useChoices";
import { useFieldProps, type ValueInputProps } from "./valueInput";
import { changeAndCommit, fromSingle } from "./values";
import ValueText from "./ValueText";

// Where the choices come from: fetched from a source, or a list already in hand, such as
// `[ { value: "pdf", label: "PDF" } ]`.
type ChoiceOrigin =
  | { source: ChoiceSource; choices?: never }
  | { choices: Choice[]; source?: never };

// One value, which may be listed under a heading, or several, shown as chips.
export type ChoiceCount =
  | {
    multiple?: false;
    // The heading a value is listed under. Values sharing one are shown together.
    groupOf?: (value: string) => string;
    chipError?: never;
  }
  | {
    multiple: true;
    // Whether a value's chip is marked in error
    chipError?: (value: string) => boolean;
    groupOf?: never;
  };

export type ChoiceSearchProps = ValueInputProps & ChoiceOrigin & ChoiceCount;

interface SingleChoiceProps extends ValueInputProps {
  choices: Choice[];
  loading: boolean;
  groupOf?: (value: string) => string;
}

// A ChoiceSearch holding one value. A required one cannot be cleared, only changed.
function SingleChoiceSearch(props: SingleChoiceProps) {
  const { values, choices, loading, groupOf, required = false, onChange, onCommit, disabled } = props;
  const { field, visit } = useFieldProps(props);
  const listed = useMemo(() => {
    const offered = choices.map(choice => choice.value);
    // A group's values have to be adjacent, or the list repeats its heading. The sort keeps each group's order.
    return groupOf === undefined ? offered
      : offered.map((value, index) => ({ value, index, group: groupOf(value) }))
        .sort((one, other) => one.group.localeCompare(other.group) || one.index - other.index)
        .map(entry => entry.value);
  }, [ choices, groupOf ]);
  const labels = useMemo(() => new Map(choices.map(choice => [ choice.value, choice.label ])), [ choices ]);
  // Set when erasing the search text cleared the value, until that clear is committed
  const clearedByTyping = useRef(false);
  const current = values.at(0);
  // A stored value the list does not offer stays selected
  const options = current === undefined || labels.has(current) ? listed : [ ...listed, current ];

  return (
    <Autocomplete<string, false, boolean>
      options={options}
      groupBy={groupOf}
      getOptionLabel={value => labels.get(value) ?? value}
      getOptionKey={value => value}
      value={current ?? null}
      loading={loading}
      disabled={disabled}
      disableClearable={required}
      onChange={(event, picked, reason) => {
        // Erasing the text clears the value. Another search usually follows, so the clear is committed only
        // when the field is left
        clearedByTyping.current = reason === "clear" && event.type === "change";
        if (clearedByTyping.current) {
          onChange([]);
        } else {
          changeAndCommit(props)(fromSingle(picked ?? ""));
        }
      }}
      onBlur={() => {
        visit();
        if (clearedByTyping.current) {
          clearedByTyping.current = false;
          onCommit?.([]);
        }
      }}
      renderInput={params => <TextField {...params} {...field} />}
    />
  );
}

// One value or several, picked from a list that narrows as it is typed.
export default function ChoiceSearch(props: ChoiceSearchProps) {
  const { source, choices: handed, multiple, groupOf, chipError, ...input } = props;
  const { label, values, problems = [], readOnly = false } = input;
  const { choices: found, loading, loadError } = useChoices(source);
  const choices = handed ?? found;
  const shown = { ...input, problems: loadError ? [ ...problems, loadError ] : problems };

  if (readOnly) {
    return <ValueText label={label} values={values} choices={choices} />;
  }
  if (multiple) {
    return <MultiValueInput {...shown} choices={choices} free={false} loading={loading} chipError={chipError} />;
  }
  return <SingleChoiceSearch {...shown} choices={choices} loading={loading} groupOf={groupOf} />;
}
