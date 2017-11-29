/*
 * Copyright 2017 Netflix, Inc.
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

package io.netflix.titus.ext.aws;

import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancingAsync;
import com.amazonaws.services.elasticloadbalancingv2.model.DeregisterTargetsRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.DescribeTargetGroupsResult;
import com.amazonaws.services.elasticloadbalancingv2.model.RegisterTargetsRequest;
import com.amazonaws.services.elasticloadbalancingv2.model.TargetDescription;
import io.netflix.titus.api.connector.cloud.CloudConnectorException;
import io.netflix.titus.api.connector.cloud.LoadBalancerConnector;
import io.netflix.titus.common.util.CollectionsExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.Observable;
import rx.Scheduler;
import rx.Single;
import rx.schedulers.Schedulers;

public class AwsLoadBalancerConnector implements LoadBalancerConnector {
    private static final Logger logger = LoggerFactory.getLogger(AwsLoadBalancerConnector.class);
    private static final String AWS_IP_TARGET_TYPE = "ip";

    private final AmazonElasticLoadBalancingAsync client;
    private final Scheduler scheduler;

    @Inject
    public AwsLoadBalancerConnector(AmazonElasticLoadBalancingAsync client) {
        this(client, Schedulers.computation());
    }

    public AwsLoadBalancerConnector(AmazonElasticLoadBalancingAsync client, Scheduler scheduler) {
        this.client = client;
        this.scheduler = scheduler;
    }

    @Override
    public Completable registerAll(String loadBalancerId, Set<String> ipAddresses) {
        if (CollectionsExt.isNullOrEmpty(ipAddresses)) {
            return Completable.complete();
        }

        // TODO: retry logic
        // TODO: handle partial failures in the batch
        // TODO: timeouts

        final Set<TargetDescription> targetDescriptions = ipAddresses.stream().map(
                ipAddress -> new TargetDescription().withId(ipAddress)
        ).collect(Collectors.toSet());
        final RegisterTargetsRequest request = new RegisterTargetsRequest()
                .withTargetGroupArn(loadBalancerId)
                .withTargets(targetDescriptions);

        // force observeOn(scheduler) since the callback will be called from the AWS SDK threadpool
        return AwsObservableExt.asyncActionCompletable(factory -> client.registerTargetsAsync(request, factory.handler(
                (req, resp) -> logger.debug("Registered targets {}", resp),
                (t) -> logger.error("Error registering targets on " + loadBalancerId, t)
        ))).observeOn(scheduler);
    }

    @Override
    public Completable deregisterAll(String loadBalancerId, Set<String> ipAddresses) {
        if (CollectionsExt.isNullOrEmpty(ipAddresses)) {
            return Completable.complete();
        }

        // TODO: retry logic
        // TODO: handle partial failures in the batch
        // TODO: timeouts

        final DeregisterTargetsRequest request = new DeregisterTargetsRequest()
                .withTargetGroupArn(loadBalancerId)
                .withTargets(ipAddresses.stream().map(
                        ipAddress -> new TargetDescription().withId(ipAddress)
                ).collect(Collectors.toSet()));

        // force observeOn(scheduler) since the callback will be called from the AWS SDK threadpool
        return AwsObservableExt.asyncActionCompletable(factory -> client.deregisterTargetsAsync(request, factory.handler(
                (req, resp) -> logger.debug("Deregistered targets {}", resp),
                (t) -> logger.error("Error deregistering targets on " + loadBalancerId, t)
        ))).observeOn(scheduler);
    }

    @Override
    public Completable isValid(String loadBalancerId) {
        final DescribeTargetGroupsRequest request = new DescribeTargetGroupsRequest()
                .withTargetGroupArns(loadBalancerId);

        Single<DescribeTargetGroupsResult> resultSingle = AwsObservableExt.asyncActionSingle(factory -> client.describeTargetGroupsAsync(request, factory.handler()));
        return resultSingle
                .flatMapObservable(result -> Observable.from(result.getTargetGroups()))
                .flatMap(targetGroup -> {
                    if (targetGroup.getTargetType().equals(AWS_IP_TARGET_TYPE)) {
                        return Observable.empty();
                    }
                    return Observable.error(CloudConnectorException.invalidArgument(String.format("Target group %s is NOT of required type %s", targetGroup.getTargetGroupArn(), AWS_IP_TARGET_TYPE)));
                })
                .observeOn(scheduler)
                .toCompletable();
    }
}
