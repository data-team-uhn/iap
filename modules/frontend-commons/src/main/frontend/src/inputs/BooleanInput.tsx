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

import { booleanChoices } from "./choices";
import ChoiceSelect from "./ChoiceSelect";
import { type ValueInputProps } from "./valueInput";

interface BooleanInputProps extends ValueInputProps {
  allowNone?: boolean;
  trueLabel?: string;
  falseLabel?: string;
  // Whether the list opens as soon as it appears
  openOnMount?: boolean;
}

// A yes or no, picked from a list and stored as "true" or "false".
export default function BooleanInput({ trueLabel, falseLabel, ...props }: BooleanInputProps) {
  return <ChoiceSelect {...props} choices={booleanChoices(trueLabel, falseLabel)} />;
}
