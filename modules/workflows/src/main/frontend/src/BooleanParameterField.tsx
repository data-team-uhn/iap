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

import BooleanInput from "@iap/frontend-commons/inputs/BooleanInput";
import { booleanChoices } from "@iap/frontend-commons/inputs/choices";
import MultiValueInput from "@iap/frontend-commons/inputs/MultiValueInput";

import { isBoolean } from "./parameterValues";
import { countLimits, inputProps } from "./parameterWidget";
import { isMultiple } from "./workflowTypes";

import type { ParameterFieldProps } from "./parameterWidget";

const CHOICES = booleanChoices();

// A parameter holding yes or no. A list of them is picked from yes and no as chips.
export default function BooleanParameterField(props: ParameterFieldProps) {
  const { parameter, readOnly = false } = props;

  if (isMultiple(parameter) && !readOnly) {
    return (
      <MultiValueInput
        {...inputProps(props)}
        {...countLimits(parameter)}
        choices={CHOICES}
        chipError={value => !isBoolean(value)}
      />
    );
  }
  return <BooleanInput {...inputProps(props)} />;
}
