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

import { type ReactNode } from "react";

import { Alert, Stack, Typography } from "@mui/material";
import { useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import { ActionsMenu } from "@iap/frontend-commons/components/ActionsMenu";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { usePageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import TagChip from "@iap/tags/TagChip";

import {
  descriptionOf, type JcrNode, labelOf, nameOf, noticeOf, pathOf, titleOf, versionsOf,
} from "./schemaModel";
import SchemaVersionActions from "./SchemaVersionActions";
import SchemaVersionTree from "./SchemaVersionTree";
import { schemaPageUrl } from "./useSchemaList";
import { useSchemaVersionTree } from "./useSchemaVersionTree";

interface SchemaVersionViewProps {
  schema: JcrNode;
  versionName: string;
  // What the schema's own page would say about it, such as that it is retired
  pageNotices: ReactNode;
  reloadSchema: () => void;
}

// One version of a schema: where it stands, what can be done with it and changed in it, and everything it
// asks of a submission. The schema it belongs to heads the title and leads back from the breadcrumb trail.
function SchemaVersionView({ schema, versionName, pageNotices, reloadSchema }: SchemaVersionViewProps) {
  const navigate = useNavigate();
  const schemaPage = schemaPageUrl(nameOf(schema));
  usePageCrumbs([ { path: schemaPage, label: titleOf(schema) } ]);
  const version = versionsOf(schema).find(candidate => candidate["@name"] === versionName);
  const { tree, loading, loadError, reload } = useSchemaVersionTree(`${pathOf(schema)}/${versionName}`);

  if (!version) {
    return (
      <AdminScreen title={`Version ${versionName}`} titlePrefix={titleOf(schema)}>
        <Typography>{`This schema has no version ${versionName}.`}</Typography>
      </AdminScreen>
    );
  }
  const notice = noticeOf(version);

  return (
    <AdminScreen
      title={`Version ${labelOf(version)}`}
      titlePrefix={titleOf(schema)}
      status={<TagChip tags={version.tags} />}
      description={descriptionOf(version)}
      action={
        <ActionsMenu label={`Actions for version ${labelOf(version)}`}>
          <SchemaVersionActions
            version={version}
            schema={schema}
            reload={() => {
              reloadSchema();
              void reload();
            }}
            removed={() => void navigate(schemaPage)}
          />
        </ActionsMenu>
      }
      disablePanel
    >
      {pageNotices}
      <Stack spacing={2}>
        { notice && (
          // Standing guidance rather than news, so it is read in place instead of interrupting
          <Alert severity="info" role="note">{notice}</Alert>
        ) }
        { loadError && <LoadError title="The version's content could not be loaded" message={loadError}
          onRetry={reload} /> }
        <LoadingOverlay open={loading} />
        { tree && <SchemaVersionTree version={tree} reload={reload} /> }
      </Stack>
    </AdminScreen>
  );
}

export default SchemaVersionView;
