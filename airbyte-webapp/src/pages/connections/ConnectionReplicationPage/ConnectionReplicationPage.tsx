import isBoolean from "lodash/isBoolean";
import React, { useCallback, useEffect } from "react";
import { useFormState } from "react-hook-form";
import { FormattedMessage, useIntl } from "react-intl";
import { useLocation } from "react-router-dom";
import { useUnmount } from "react-use";

import { ConnectionConfigurationCard } from "components/connection/ConnectionForm/ConnectionConfigurationCard";
import {
  FormConnectionFormValues,
  useConnectionValidationSchema,
} from "components/connection/ConnectionForm/formConfig";
import { useRefreshSourceSchemaWithConfirmationOnDirty } from "components/connection/ConnectionForm/refreshSourceSchemaWithConfirmationOnDirty";
import { SchemaChangeBackdrop } from "components/connection/ConnectionForm/SchemaChangeBackdrop";
import { SyncCatalogCard } from "components/connection/ConnectionForm/SyncCatalogCard";
import { SyncCatalogCardNext } from "components/connection/ConnectionForm/SyncCatalogCardNext";
import { UpdateConnectionFormControls } from "components/connection/ConnectionForm/UpdateConnectionFormControls";
import { SchemaError } from "components/connection/CreateConnectionForm/SchemaError";
import { compareObjectsByFields } from "components/connection/syncCatalog/utils";
import { Form } from "components/forms";
import LoadingSchema from "components/LoadingSchema";
import { FlexContainer } from "components/ui/Flex";
import { Message } from "components/ui/Message/Message";

import { ConnectionValues, useGetStateTypeQuery } from "core/api";
import {
  AirbyteStreamAndConfiguration,
  AirbyteStreamConfiguration,
  WebBackendConnectionRead,
  WebBackendConnectionUpdate,
} from "core/api/types/AirbyteClient";
import { PageTrackingCodes, useTrackPage } from "core/services/analytics";
import { equal } from "core/utils/objects";
import { useConfirmCatalogDiff } from "hooks/connection/useConfirmCatalogDiff";
import { useSchemaChanges } from "hooks/connection/useSchemaChanges";
import { useConnectionEditService } from "hooks/services/ConnectionEdit/ConnectionEditService";
import { useConnectionFormService } from "hooks/services/ConnectionForm/ConnectionFormService";
import { useExperiment } from "hooks/services/Experiment";
import { useModalService } from "hooks/services/Modal";

import styles from "./ConnectionReplicationPage.module.scss";
import { ResetWarningModal } from "./ResetWarningModal";
import { useAnalyticsTrackFunctions } from "./useAnalyticsTrackFunctions";

const toWebBackendConnectionUpdate = (connection: WebBackendConnectionRead): WebBackendConnectionUpdate => ({
  name: connection.name,
  connectionId: connection.connectionId,
  namespaceDefinition: connection.namespaceDefinition,
  namespaceFormat: connection.namespaceFormat,
  prefix: connection.prefix,
  syncCatalog: connection.syncCatalog,
  scheduleData: connection.scheduleData,
  scheduleType: connection.scheduleType,
  status: connection.status,
  resourceRequirements: connection.resourceRequirements,
  operations: connection.operations,
  sourceCatalogId: connection.catalogId,
});

const SchemaChangeMessage: React.FC = () => {
  const { isDirty } = useFormState<FormConnectionFormValues>();
  const refreshWithConfirm = useRefreshSourceSchemaWithConfirmationOnDirty(isDirty);

  const { refreshSchema } = useConnectionFormService();
  const { connection, schemaHasBeenRefreshed } = useConnectionEditService();
  const { hasNonBreakingSchemaChange, hasBreakingSchemaChange } = useSchemaChanges(connection.schemaChange);

  if (schemaHasBeenRefreshed) {
    return null;
  }

  if (hasNonBreakingSchemaChange) {
    return (
      <Message
        type="info"
        text={<FormattedMessage id="connection.schemaChange.nonBreaking" />}
        actionBtnText={<FormattedMessage id="connection.schemaChange.reviewAction" />}
        onAction={refreshSchema}
        data-testid="schemaChangesDetected"
      />
    );
  }

  if (hasBreakingSchemaChange) {
    return (
      <Message
        type="error"
        text={<FormattedMessage id="connection.schemaChange.breaking" />}
        actionBtnText={<FormattedMessage id="connection.schemaChange.reviewAction" />}
        onAction={refreshWithConfirm}
        data-testid="schemaChangesDetected"
      />
    );
  }
  return null;
};

export const ConnectionReplicationPage: React.FC = () => {
  useTrackPage(PageTrackingCodes.CONNECTIONS_ITEM_REPLICATION);
  const isSyncCatalogV2Enabled = useExperiment("connection.syncCatalogV2", false);
  const { trackSchemaEdit } = useAnalyticsTrackFunctions();

  const getStateType = useGetStateTypeQuery();

  const { formatMessage } = useIntl();
  const { openModal } = useModalService();

  const { connection, schemaRefreshing, updateConnection, discardRefreshedSchema } = useConnectionEditService();
  const { initialValues, schemaError, setSubmitError, refreshSchema, mode } = useConnectionFormService();
  const validationSchema = useConnectionValidationSchema();

  const saveConnection = useCallback(
    async (values: ConnectionValues, skipReset: boolean) => {
      const connectionAsUpdate = toWebBackendConnectionUpdate(connection);

      await updateConnection({
        ...connectionAsUpdate,
        ...values,
        connectionId: connection.connectionId,
        skipReset,
      });
    },
    [connection, updateConnection]
  );

  const onFormSubmit = useCallback(
    async (values: FormConnectionFormValues) => {
      // Check if the user refreshed the catalog and there was any change in a currently enabled stream
      const hasCatalogDiffInEnabledStream = connection.catalogDiff?.transforms.some(({ streamDescriptor }) => {
        // Find the stream for this transform in our form's syncCatalog
        const stream = values.syncCatalog.streams.find(
          ({ stream }) => streamDescriptor.name === stream?.name && streamDescriptor.namespace === stream.namespace
        );
        return stream?.config?.selected;
      });

      // Check if the user made any modifications to enabled streams compared to the ones in the latest connection
      // e.g. changed the sync mode of an enabled stream
      const hasUserChangesInEnabledStreams = !equal(
        values.syncCatalog.streams.filter((s) => s.config?.selected),
        connection.syncCatalog.streams.filter((s) => s.config?.selected)
      );

      // Only adding/removing a stream - with 0 other changes - doesn't require a reset
      // for each form value stream, find the corresponding connection value stream
      // and remove `config.selected` from both before comparing
      // we need to reset if any of the streams are different
      const getStreamId = (stream: AirbyteStreamAndConfiguration) => {
        return `${stream.stream?.namespace ?? ""}-${stream.stream?.name}`;
      };

      const lookupConnectionValuesStreamById = connection.syncCatalog.streams.reduce<
        Record<string, AirbyteStreamAndConfiguration>
      >((agg, stream) => {
        agg[getStreamId(stream)] = stream;
        return agg;
      }, {});

      const hasUserChangesInEnabledStreamsRequiringReset = values.syncCatalog.streams
        .filter((streamNode) => streamNode.config?.selected)
        .some((streamNode) => {
          const formStream = structuredClone(streamNode);
          const connectionStream = structuredClone(lookupConnectionValuesStreamById[getStreamId(formStream)]);

          return !compareObjectsByFields<AirbyteStreamConfiguration>(formStream.config, connectionStream.config, [
            "cursorField",
            "destinationSyncMode",
            "primaryKey",
            "selectedFields",
            "syncMode",
            "aliasName",
          ]);
        });

      const catalogChangesRequireReset = hasCatalogDiffInEnabledStream || hasUserChangesInEnabledStreamsRequiringReset;

      setSubmitError(null);

      // Whenever the catalog changed show a warning to the user, that we're about to reset their data.
      // Given them a choice to opt-out in which case we'll be sending skipReset: true to the update
      // endpoint.
      try {
        if (catalogChangesRequireReset) {
          const stateType = await getStateType(connection.connectionId);
          const result = await openModal<boolean>({
            title: formatMessage({ id: "connection.clearDataModalTitle" }),
            size: "md",
            content: (props) => <ResetWarningModal {...props} stateType={stateType} />,
          });
          if (result.type === "completed" && isBoolean(result.reason)) {
            // Save the connection taking into account the correct skipReset value from the dialog choice.
            await saveConnection(values, !result.reason /* skipReset */);
          } else {
            // We don't want to set saved to true or schema has been refreshed to false.
            return Promise.reject();
          }
        } else {
          // The catalog hasn't changed, or only added/removed stream(s). We don't need to ask for any confirmation and can simply save.
          await saveConnection(values, true /* skipReset */);
        }

        /* analytics */
        if (hasCatalogDiffInEnabledStream || hasUserChangesInEnabledStreams) {
          trackSchemaEdit(connection);
        }

        return Promise.resolve();
      } catch (e) {
        setSubmitError(e);
      }
    },
    [connection, setSubmitError, getStateType, openModal, formatMessage, saveConnection, trackSchemaEdit]
  );

  useConfirmCatalogDiff();

  useUnmount(() => {
    discardRefreshedSchema();
  });

  const { state } = useLocation();
  useEffect(() => {
    if (typeof state === "object" && state && "triggerRefreshSchema" in state && state.triggerRefreshSchema) {
      refreshSchema();
    }
  }, [refreshSchema, state]);

  const isSimpliedCreation = useExperiment("connection.simplifiedCreation", true);

  return (
    <FlexContainer direction="column" className={styles.content}>
      {schemaError && !schemaRefreshing ? (
        <SchemaError schemaError={schemaError} refreshSchema={refreshSchema} />
      ) : !schemaRefreshing && connection ? (
        <Form<FormConnectionFormValues>
          defaultValues={initialValues}
          schema={validationSchema}
          onSubmit={onFormSubmit}
          trackDirtyChanges
          disabled={mode === "readonly"}
        >
          <FlexContainer direction="column">
            <SchemaChangeMessage />
            <SchemaChangeBackdrop>
              {!isSimpliedCreation && <ConnectionConfigurationCard />}
              {isSyncCatalogV2Enabled ? <SyncCatalogCardNext /> : <SyncCatalogCard />}
              <div className={styles.editControlsContainer}>
                <UpdateConnectionFormControls onCancel={discardRefreshedSchema} />
              </div>
            </SchemaChangeBackdrop>
          </FlexContainer>
        </Form>
      ) : (
        <LoadingSchema />
      )}
    </FlexContainer>
  );
};
