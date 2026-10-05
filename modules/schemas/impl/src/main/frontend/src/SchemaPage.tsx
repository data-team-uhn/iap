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

import { type ReactNode, useCallback } from "react";

import { Alert } from "@mui/material";
import { useLocation, useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import TagChip from "@iap/tags/TagChip";

import SchemaActions from "./SchemaActions";
import { type JcrNode, schemaNameFromRoute, tagsOf, titleOf, versionNameFromRoute } from "./schemaModel";
import SchemaVersionList from "./SchemaVersionList";
import SchemaVersionView from "./SchemaVersionView";
import { useSchema } from "./useSchema";

// What stays true of the schema on its page and on each of its versions' pages
function SchemaNotices({ schema, loadError, reload }: {
  schema: JcrNode;
  loadError?: string;
  reload: () => Promise<void>;
}) {
  return (
    <>
      { loadError && <LoadError title="The schema could not be reloaded" message={loadError} onRetry={reload}
        sx={{ mb: 2 }} /> }
      { tagsOf(schema).includes("retired") && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          This schema is retired. None of its versions accepts new submissions.
        </Alert>
      ) }
    </>
  );
}

// One schema's page: its versions and where each stands, and the lifecycle actions on them and on
// the schema as a whole. With a version named, that version's own page.
function SchemaPage() {
  const { pathname } = useLocation();
  const versionName = versionNameFromRoute(pathname);
  const navigate = useNavigate();
  const name = schemaNameFromRoute(pathname);
  const { schema, loading, loadError, reload } = useSchema(name);
  const reloadSchema = useCallback(() => void reload(), [ reload ]);

  if (!schema) {
    return (
      <AdminScreen title="Schema">
        <LoadingOverlay open={loading} />
        { loadError && <LoadError title="The schema could not be loaded" message={loadError} onRetry={reload} /> }
      </AdminScreen>
    );
  }

  const notices: ReactNode = <SchemaNotices schema={schema} loadError={loadError} reload={reload} />;

  if (versionName) {
    return (
      <SchemaVersionView schema={schema} versionName={versionName} notices={notices} reloadSchema={reloadSchema} />
    );
  }

  return (
    <AdminScreen
      title={titleOf(schema)}
      status={<TagChip tags={schema.tags} />}
      description={"A draft version can change in any way. Once active, only its wording can change. Create a "
        + "new version to change anything else."}
      action={<SchemaActions schema={schema} reload={reloadSchema}
        removed={() => void navigate("/admin/schemas")} />}
      disablePanel
    >
      {notices}
      <SchemaVersionList schema={schema} reload={reloadSchema} />
    </AdminScreen>
  );
}

export default SchemaPage;
