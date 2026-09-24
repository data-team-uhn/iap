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
  Radio,
  RadioGroup,
  Typography
} from "@mui/material";

import { type FormAnswerOption, isMultiple, isRequired } from "../submissionForm";
import { getOptionFrame } from "./answerFrame";
import AnswerRow from "./AnswerRow";
import QuestionText, { getQuestionTextId } from "./QuestionText";

import type { AnswerComponentCandidate, AnswerComponentProps } from "../answerComponents";

// A question answered by picking from the answers it offers.
//
// Picking is a finished answer as it happens, like a tick and unlike typing, so it saves on change.
//
// What is stored is the option's *value*, never its label: the value is what a condition compares
// against, so a schema may reword a label — or translate it — without changing the meaning of any
// answer already recorded, and without changing which questions a request goes on to ask.
function ChoiceAnswer({ question, values, disabled, onAnswered, suggested, aside }: AnswerComponentProps) {
  const labelledBy = getQuestionTextId(question);

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
        ? question.options.filter(option => option.value === value || values.includes(option.value))
          .map(option => option.value)
        : values.filter(current => current !== value));

    return (
      <FormControl fullWidth disabled={disabled} required={isRequired(question)}>
        <QuestionText question={question} />
        <AnswerRow aside={aside}>
          <FormGroup role="group" aria-labelledby={labelledBy}>
            {question.options.map(option => (
              <FormControlLabel
                key={option.value}
                label={optionLabel(option)}
                sx={{ alignSelf: "flex-start", ...getOptionFrame(suggested === true && values.includes(option.value)) }}
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
        </AnswerRow>
        {help && <FormHelperText>{help}</FormHelperText>}
      </FormControl>
    );
  }

  const help = question.description;
  return (
    <FormControl fullWidth disabled={disabled} required={isRequired(question)}>
      <QuestionText question={question} />
      <AnswerRow aside={aside}>
        <RadioGroup
          aria-labelledby={labelledBy}
          value={values[0] ?? ""}
          onChange={event => onAnswered([ event.target.value ])}
        >
          {question.options.map(option => (
            <FormControlLabel
              key={option.value}
              value={option.value}
              label={optionLabel(option)}
              sx={{ alignSelf: "flex-start", ...getOptionFrame(suggested === true && values[0] === option.value) }}
              control={<Radio />}
            />
          ))}
        </RadioGroup>
      </AnswerRow>
      {help && <FormHelperText>{help}</FormHelperText>}
    </FormControl>
  );
}

// The label is what gets picked. The description, when the schema wrote one, says what that pick means.
function optionLabel(option: FormAnswerOption) {
  if (!option.description) {
    return option.label;
  }
  return (
    <>
      {option.label}
      <Typography variant="description" component="span" sx={{ display: "block" }}>
        {option.description}
      </Typography>
    </>
  );
}

// Offering a fixed set of answers is a stronger statement about a question than its data type is,
// so this outbids the component that would otherwise type the answer in
export const choiceAnswerCandidate: AnswerComponentCandidate = question =>
  question.options.length > 0 ? [ ChoiceAnswer, 60 ] : null;

export default ChoiceAnswer;
