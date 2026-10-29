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

import ChoiceSelect from "@iap/frontend-commons/inputs/ChoiceSelect";
import DateInput from "@iap/frontend-commons/inputs/DateInput";
import MultiValueInput from "@iap/frontend-commons/inputs/MultiValueInput";
import NumberInput from "@iap/frontend-commons/inputs/NumberInput";
import TextInput from "@iap/frontend-commons/inputs/TextInput";

import { choicesOf, type OperandShape, valueProblem } from "./conditionModel";

interface OperandValueInputProps {
  label: string;
  // What the operand compared with holds, which decides what values can be entered
  shape: OperandShape;
  several: boolean;
  required?: boolean;
  // Whether its choices open as soon as it appears
  open?: boolean;
  value: string[];
  disabled: boolean;
  onChange: (value: string[]) => void;
}

// The values an operand is compared with: picked among the choices when there are some, or else entered as the
// type compared calls for, one or several
function OperandValueInput(props: OperandValueInputProps) {
  const { label, shape, several, required, open = false, value, disabled, onChange } = props;
  const choices = choicesOf(shape);
  const problems = value.map(item => valueProblem(item, shape.type)).filter(found => found !== undefined).slice(0, 1);
  const common = { label, required, disabled, problems, values: value, onChange };

  if (several) {
    return <MultiValueInput {...common} choices={choices} openOnMount={open} />;
  }
  if (choices.length > 0) {
    return <ChoiceSelect {...common} choices={choices} allowNone={false} openOnMount={open} />;
  }
  if (shape.type === "long") {
    return <NumberInput {...common} kind="whole" />;
  }
  if (shape.type === "double" || shape.type === "decimal") {
    return <NumberInput {...common} kind="decimal" />;
  }
  if (shape.type === "date") {
    return <DateInput {...common} />;
  }
  return <TextInput {...common} />;
}

export default OperandValueInput;
