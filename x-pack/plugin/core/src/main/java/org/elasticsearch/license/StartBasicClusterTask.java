/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.license;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.core.XPackPlugin;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StartBasicClusterTask implements ClusterStateTaskListener {

    private static final String ACKNOWLEDGEMENT_HEADER = "This license update requires acknowledgement. To acknowledge the license, "
        + "please read the following messages and call /start_basic again, this time with the \"acknowledge=true\" parameter:";

    private final Logger logger;
    private final String clusterName;
    private final PostStartBasicRequest request;
    private final String description;
    private final ActionListener<PostStartBasicResponse> listener;
    private final Clock clock;

    StartBasicClusterTask(
        Logger logger,
        String clusterName,
        Clock clock,
        PostStartBasicRequest request,
        String description,
        ActionListener<PostStartBasicResponse> listener
    ) {
        this.logger = logger;
        this.clusterName = clusterName;
        this.request = request;
        this.description = description;
        this.listener = listener;
        this.clock = clock;
    }

    @Override
    public void clusterStateProcessed(ClusterState oldState, ClusterState newState) {
        assert false : "never called";
    }

    public LicensesMetadata execute(
        LicensesMetadata currentLicensesMetadata,
        DiscoveryNodes discoveryNodes,
        ClusterStateTaskExecutor.TaskContext<StartBasicClusterTask> taskContext
    ) throws Exception {
        assert taskContext.getTask() == this;
        final var listener = ActionListener.runBefore(
            this.listener,
            () -> logger.debug("license prior to starting basic license: {}", currentLicensesMetadata)
        );
        License currentLicense = LicensesMetadata.extractLicense(currentLicensesMetadata);
        final LicensesMetadata updatedLicensesMetadata;
        if (shouldGenerateNewBasicLicense(currentLicense)) {
            License selfGeneratedLicense = generateBasicLicense(discoveryNodes);
            if (request.isAcknowledged() == false && currentLicense != null) {
                Map<String, String[]> ackMessageMap = LicenseService.getAckMessages(selfGeneratedLicense, currentLicense);
                if (ackMessageMap.isEmpty() == false) {
                    taskContext.success(
                        listener.delegateFailure(
                            (delegate, ignored) -> delegate.onResponse(
                                new PostStartBasicResponse(
                                    PostStartBasicResponse.Status.NEED_ACKNOWLEDGEMENT,
                                    ackMessageMap,
                                    ACKNOWLEDGEMENT_HEADER
                                )
                            )
                        )
                    );
                    return currentLicensesMetadata;
                }
            }
            Version trialVersion = currentLicensesMetadata != null ? currentLicensesMetadata.getMostRecentTrialVersion() : null;
            updatedLicensesMetadata = new LicensesMetadata(selfGeneratedLicense, trialVersion);
        } else {
            updatedLicensesMetadata = currentLicensesMetadata;
        }
        final var responseStatus = currentLicense != null && License.LicenseType.isBasic(currentLicense.type())
            ? PostStartBasicResponse.Status.ALREADY_USING_BASIC
            : PostStartBasicResponse.Status.GENERATED_BASIC;
        taskContext.success(listener.delegateFailure((l, s) -> l.onResponse(new PostStartBasicResponse(responseStatus))));
        return updatedLicensesMetadata;
    }

    @Override
    public void onFailure(@Nullable Exception e) {
        logger.error(new ParameterizedMessage("unexpected failure during [{}]", description), e);
        listener.onFailure(e);
    }

    private boolean shouldGenerateNewBasicLicense(License currentLicense) {
        return currentLicense == null
            || License.LicenseType.isBasic(currentLicense.type()) == false
            || LicenseService.SELF_GENERATED_LICENSE_MAX_NODES != currentLicense.maxNodes()
            || LicenseService.BASIC_SELF_GENERATED_LICENSE_EXPIRATION_MILLIS != LicenseService.getExpiryDate(currentLicense);
    }

    private License generateBasicLicense(DiscoveryNodes discoveryNodes) {
        final License.Builder specBuilder = License.builder()
            .uid(UUID.randomUUID().toString())
            .issuedTo(clusterName)
            .maxNodes(LicenseService.SELF_GENERATED_LICENSE_MAX_NODES)
            .issueDate(clock.millis())
            .type(License.LicenseType.BASIC)
            .expiryDate(LicenseService.BASIC_SELF_GENERATED_LICENSE_EXPIRATION_MILLIS);

        return SelfGeneratedLicense.create(specBuilder, discoveryNodes);
    }

    public String getDescription() {
        return description;
    }

    static class Executor implements ClusterStateTaskExecutor<StartBasicClusterTask> {
        @Override
        public ClusterState execute(ClusterState currentState, List<TaskContext<StartBasicClusterTask>> taskContexts) throws Exception {
            XPackPlugin.checkReadyForXPackCustomMetadata(currentState);
            final LicensesMetadata originalLicensesMetadata = currentState.metadata().custom(LicensesMetadata.TYPE);
            var currentLicensesMetadata = originalLicensesMetadata;
            for (final var taskContext : taskContexts) {
                currentLicensesMetadata = taskContext.getTask().execute(currentLicensesMetadata, currentState.nodes(), taskContext);
            }
            if (currentLicensesMetadata == originalLicensesMetadata) {
                return currentState;
            } else {
                return ClusterState.builder(currentState)
                    .metadata(Metadata.builder(currentState.metadata()).putCustom(LicensesMetadata.TYPE, currentLicensesMetadata))
                    .build();
            }
        }
    }
}
