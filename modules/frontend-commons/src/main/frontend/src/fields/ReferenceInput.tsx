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

import { type ContentField } from "./fieldsModel";
import ChoiceSearch from "../inputs/ChoiceSearch";
import MultiValueInput from "../inputs/MultiValueInput";
import { referenceChoices } from "../inputs/referenceChoices";
import TextInput from "../inputs/TextInput";
import { fromSingle, singleValue } from "../inputs/valueInput";

interface ReferenceInputProps {
  field: ContentField;
  // The path of each node pointed at
  value: string | string[];
  disabled: boolean;
  helperText?: string;
  onChange: (value: string | string[]) => void;
}

// Picks what a reference field points at among the nodes it may. A field naming no type takes any path.
function ReferenceInput({ field, value, disabled, helperText, onChange }: ReferenceInputProps) {
  const { label, mandatory, multiple, referenceType, referenceRoot } = field;
  const common = {
    label, description: helperText, required: mandatory, disabled,
    values: Array.isArray(value) ? value : fromSingle(value),
    onChange: (next: string[]) => onChange(multiple ? next : singleValue(next)),
  };
  if (!referenceType) {
    return multiple ? <MultiValueInput {...common} /> : <TextInput {...common} />;
  }
  return (
    <ChoiceSearch
      {...common}
      multiple={multiple}
      source={referenceChoices({ resourceType: referenceType, root: referenceRoot })}
    />
  );
}

export default ReferenceInput;
