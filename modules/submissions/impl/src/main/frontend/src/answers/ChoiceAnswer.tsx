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
  Checkbox,
  FormControl,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  FormLabel,
  Radio,
  RadioGroup
} from "@mui/material";

import { questionLabel } from "./label";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A question answered by picking from the answers it offers.
//
// Picking is a finished answer as it happens, like a tick and unlike typing, so it saves on change.
//
// What is stored is the option's *value*, never its label: the value is what a condition compares
// against, so a schema may reword a label — or translate it — without changing the meaning of any
// answer already recorded, and without changing which questions a request goes on to ask.
function ChoiceAnswer({ question, values, disabled, onAnswered }: AnswerComponentProps) {
  const label = questionLabel(question);
  const help = question.description;

  if (question.multiple) {
    const toggle = (value: string, checked: boolean) =>
      onAnswered(checked
        // Kept in the offered order rather than the order they were clicked, so that two people
        // answering the same way store the same thing
        ? question.options.filter(option => option.value === value || values.includes(option.value))
          .map(option => option.value)
        : values.filter(current => current !== value));

    return (
      <FormControl component="fieldset" disabled={disabled} required={question.required}>
        <FormLabel component="legend">{label}</FormLabel>
        <FormGroup>
          {question.options.map(option => (
            <FormControlLabel
              key={option.value}
              label={option.label}
              control={
                <Checkbox
                  checked={values.includes(option.value)}
                  onChange={event => toggle(option.value, event.target.checked)}
                />
              }
            />
          ))}
        </FormGroup>
        {help && <FormHelperText>{help}</FormHelperText>}
      </FormControl>
    );
  }

  return (
    <FormControl disabled={disabled} required={question.required}>
      <FormLabel id={`${question.path}-label`}>{label}</FormLabel>
      <RadioGroup
        aria-labelledby={`${question.path}-label`}
        value={values[0] ?? ""}
        onChange={event => onAnswered([ event.target.value ])}
      >
        {question.options.map(option => (
          <FormControlLabel
            key={option.value}
            value={option.value}
            label={option.label}
            control={<Radio />}
          />
        ))}
      </RadioGroup>
      {help && <FormHelperText>{help}</FormHelperText>}
    </FormControl>
  );
}

// Offering a fixed set of answers is a stronger statement about a question than its data type is,
// so this outbids the component that would otherwise type the answer in
export const choiceAnswerCandidate: AnswerComponentCandidate = question =>
  question.options.length > 0 ? [ ChoiceAnswer, 60 ] : null;

export default ChoiceAnswer;
