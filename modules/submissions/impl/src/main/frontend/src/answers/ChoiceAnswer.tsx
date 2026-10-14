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
  Button,
  Checkbox,
  FormControl,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  Radio,
  RadioGroup
} from "@mui/material";

import { registerAnswerComponent } from "../answerComponents";
import { answerCap, countGuidance, isMultiple, isRequired } from "../submissionForm";
import QuestionText, { getQuestionTextId } from "./QuestionText";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A question answered by picking from the options it offers.
function ChoiceAnswer({ question, values, disabled, onAnswered }: AnswerComponentProps) {
  const labelledBy = getQuestionTextId(question);
  const help = question.description;
  const { options } = question;

  if (isMultiple(question)) {
    // At the cap, the unchecked boxes are disabled, since the save would refuse another pick
    const cap = answerCap(question);
    const atCap = cap !== undefined && values.length >= cap;
    const guidance = [ help, countGuidance(question, "Choose") ].filter(Boolean).join(" ");
    const toggle = (value: string, checked: boolean) =>
      onAnswered(checked
        // In the offered order, not the order of the clicks, so that the same picks store the same
        // answer
        ? options.filter(option => option.value === value || values.includes(option.value))
          .map(option => option.value)
        : values.filter(current => current !== value));

    return (
      <FormControl fullWidth disabled={disabled} required={isRequired(question)}>
        <QuestionText question={question} />
        <FormGroup role="group" aria-labelledby={labelledBy}>
          {options.map(option => (
            <FormControlLabel
              key={option.value}
              label={option.label}
              control={
                <Checkbox
                  checked={values.includes(option.value)}
                  disabled={disabled || (atCap && !values.includes(option.value))}
                  onChange={event => toggle(option.value, event.target.checked)}
                />
              }
            />
          ))}
        </FormGroup>
        {guidance && <FormHelperText>{guidance}</FormHelperText>}
      </FormControl>
    );
  }

  return (
    <FormControl fullWidth disabled={disabled} required={isRequired(question)}>
      <QuestionText question={question} />
      <RadioGroup
        aria-labelledby={labelledBy}
        value={values[0] ?? ""}
        onChange={event => onAnswered([ event.target.value ])}
      >
        {options.map(option => (
          <FormControlLabel
            key={option.value}
            value={option.value}
            label={option.label}
            control={<Radio required={isRequired(question)} />}
          />
        ))}
      </RadioGroup>
      {/* A picked radio cannot be unpicked, so an optional answer gets a way back to none */}
      {!isRequired(question) && values.length > 0 && (
        <Button size="small" disabled={disabled} onClick={() => onAnswered([])} sx={{ alignSelf: "start" }}>
          Clear
        </Button>
      )}
      {help && <FormHelperText>{help}</FormHelperText>}
    </FormControl>
  );
}

// A question that offers answers is answered by picking, whatever its data type, so this outbids the
// components chosen by data type
export const choiceAnswerCandidate: AnswerComponentCandidate = question =>
  question.options.length > 0 ? [ ChoiceAnswer, 60 ] : null;

registerAnswerComponent(choiceAnswerCandidate);

export default ChoiceAnswer;
