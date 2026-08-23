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

import { registerAnswerComponent } from "../answerComponents";
import { isMultiple, isRequired } from "../submissionForm";
import { questionLabel } from "./label";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A question answered by picking from the options it offers.
function ChoiceAnswer({ question, values, disabled, onAnswered }: AnswerComponentProps) {
  const label = questionLabel(question);
  const options = question.options ?? [];

  if (isMultiple(question)) {
    // A capped list stops offering at the cap instead of letting a pick be made and refused: the
    // unchecked boxes grey out, which also *shows* the rule rather than merely enforcing it
    const capped = question.maxAnswers > 1;
    const atCap = capped && values.length >= question.maxAnswers;
    const counts = [
      question.minAnswers > 1 ? `Choose at least ${question.minAnswers}.` : null,
      capped ? `Choose up to ${question.maxAnswers}.` : null,
    ].filter(Boolean).join(" ");
    const help = [ question.description, counts ].filter(Boolean).join(" ");
    const toggle = (value: string, checked: boolean) =>
      onAnswered(checked
        // Kept in the offered order rather than the order they were clicked, so that two people
        // answering the same way store the same thing
        ? options.filter(option => option.value === value || values.includes(option.value))
          .map(option => option.value)
        : values.filter(current => current !== value));

    return (
      <FormControl component="fieldset" disabled={disabled} required={isRequired(question)}>
        <FormLabel component="legend">{label}</FormLabel>
        <FormGroup>
          {options.map(option => (
            <FormControlLabel
              key={option.value}
              label={option.label}
              control={
                <Checkbox
                  checked={values.includes(option.value)}
                  disabled={atCap && !values.includes(option.value)}
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

  const help = question.description;
  return (
    <FormControl disabled={disabled} required={isRequired(question)}>
      <FormLabel id={`${question.path}-label`}>{label}</FormLabel>
      <RadioGroup
        aria-labelledby={`${question.path}-label`}
        value={values[0] ?? ""}
        onChange={event => onAnswered([ event.target.value ])}
      >
        {options.map(option => (
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

// Offering a fixed set of answers says more about a question than its data type does, so this
// outbids the component that would otherwise type the answer in
export const choiceAnswerCandidate: AnswerComponentCandidate = question =>
  (question.options ?? []).length > 0 ? [ ChoiceAnswer, 60 ] : null;

registerAnswerComponent(choiceAnswerCandidate);

export default ChoiceAnswer;
