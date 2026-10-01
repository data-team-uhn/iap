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

import { Autocomplete, Box, TextField, Typography } from "@mui/material";

import type { OperandEditorProps } from "@iap/conditions/ConditionBuilder";
import { withCurrent } from "@iap/conditions/conditionModel";

import CodePill from "./CodePill";
import { type JcrNode, nameOf } from "./schemaModel";
import { headingOf } from "./schemaVersionTreeModel";
import { useVersionConditions } from "./versionConditions";

interface QuestionPickerProps extends OperandEditorProps {
  // The questions that may be picked, in the version's order
  questions: JcrNode[];
}

// Picks the question an answer operand reads, found by what it asks and named by its identifier, which is how
// the condition keeps naming it wherever it moves. One named otherwise, as by hand, is shown as the question it is.
function QuestionPicker({ label, value, disabled, required, onChange, questions }: QuestionPickerProps) {
  const { index } = useVersionConditions();
  const current = value.at(0);
  const headingFor = (reference: string) => {
    const question = index.find(reference);
    return question ? headingOf(question) : reference;
  };
  const choices = withCurrent(questions.map(question => ({
    value: String(question["jcr:uuid"]),
    label: headingOf(question),
  })), current, headingFor);
  return (
    <Autocomplete<string>
      options={choices.map(choice => choice.value)}
      getOptionLabel={headingFor}
      value={current ?? null}
      disabled={disabled}
      onChange={(_event, picked) => onChange(picked === null ? [] : [ picked ])}
      renderOption={({ key, ...props }, reference) => {
        const question = index.find(reference);
        return (
          <Box component="li" key={key} {...props} sx={{ gap: 1, flexWrap: "wrap" }}>
            <Typography>{headingFor(reference)}</Typography>
            { question && <CodePill name={nameOf(question)} /> }
          </Box>
        );
      }}
      renderInput={params => <TextField {...params} label={label} required={required} />}
    />
  );
}

export default QuestionPicker;
