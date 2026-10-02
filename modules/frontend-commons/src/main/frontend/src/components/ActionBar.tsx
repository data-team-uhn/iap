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

import { Fragment, useEffect, useState } from "react";

import { Stack } from "@mui/material";

import { getActions, type ActionComponent } from "../actionsManager";
import { useInActionsMenu } from "./ActionsMenu";

// The actions contributed on one extension point, rendered in order with the props the page gives
// them. Each decides for itself whether it applies.
function ActionBar({ point, ...props }: { point: string } & Record<string, unknown>) {
  const [ actions, setActions ] = useState<ActionComponent[]>([]);
  const inMenu = useInActionsMenu();

  useEffect(() => {
    let cancelled = false;
    void getActions(point).then(loaded => {
      if (!cancelled) {
        setActions(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [ point ]);

  const shown = actions.map((Action, index) => <Fragment key={`action-${index}`}><Action {...props} /></Fragment>);
  // A menu's lines have to be its own children
  return inMenu ? shown : (
    <Stack direction="row" spacing={0.5} sx={{ alignItems: "center", flexWrap: "wrap" }}>
      {shown}
    </Stack>
  );
}

export default ActionBar;
