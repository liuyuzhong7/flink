/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.resourcemanager;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.concurrent.ApplyFunction;
import org.apache.flink.runtime.concurrent.Future;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.jobmaster.JobMasterRegistrationSuccess;
import org.apache.flink.runtime.leaderelection.LeaderContender;
import org.apache.flink.runtime.leaderelection.LeaderElectionService;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalListener;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalService;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManager;
import org.apache.flink.runtime.rpc.RpcMethod;
import org.apache.flink.runtime.rpc.RpcEndpoint;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.jobmaster.JobMaster;
import org.apache.flink.runtime.jobmaster.JobMasterGateway;
import org.apache.flink.runtime.taskexecutor.TaskExecutorRegistrationSuccess;
import org.apache.flink.runtime.registration.RegistrationResponse;

import org.apache.flink.runtime.concurrent.Future;

import org.apache.flink.runtime.util.LeaderConnectionInfo;
import org.apache.flink.runtime.util.LeaderRetrievalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.FiniteDuration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * ResourceManager implementation. The resource manager is responsible for resource de-/allocation
 * and bookkeeping.
 *
 * It offers the following methods as part of its rpc interface to interact with the him remotely:
 * <ul>
 *     <li>{@link #registerJobMaster(UUID, String, JobID)} registers a {@link JobMaster} at the resource manager</li>
 *     <li>{@link #requestSlot(SlotRequest)} requests a slot from the resource manager</li>
 * </ul>
 */
public class ResourceManager extends RpcEndpoint<ResourceManagerGateway> implements LeaderContender {

	private final Logger LOG = LoggerFactory.getLogger(getClass());

	private final Map<JobID, JobMasterGateway> jobMasterGateways;

	private final Set<LeaderRetrievalListener> jobMasterLeaderRetrievalListeners;

	private final HighAvailabilityServices highAvailabilityServices;

	private LeaderElectionService leaderElectionService;

	private final SlotManager slotManager;

	private UUID leaderSessionID;

	public ResourceManager(
			RpcService rpcService,
			HighAvailabilityServices highAvailabilityServices,
			SlotManager slotManager) {
		super(rpcService);
		this.highAvailabilityServices = checkNotNull(highAvailabilityServices);
		this.jobMasterGateways = new HashMap<>();
		this.slotManager = slotManager;
		this.jobMasterLeaderRetrievalListeners = new HashSet<>();
	}

	@Override
	public void start() {
		// start a leader
		try {
			super.start();
			leaderElectionService = highAvailabilityServices.getResourceManagerLeaderElectionService();
			leaderElectionService.start(this);
		} catch (Throwable e) {
			log.error("A fatal error happened when starting the ResourceManager", e);
			throw new RuntimeException("A fatal error happened when starting the ResourceManager", e);
		}
	}

	@Override
	public void shutDown() {
		try {
			leaderElectionService.stop();
			for(JobID jobID : jobMasterGateways.keySet()) {
				highAvailabilityServices.getJobMasterLeaderRetriever(jobID).stop();
			}
			super.shutDown();
		} catch (Throwable e) {
			log.error("A fatal error happened when shutdown the ResourceManager", e);
			throw new RuntimeException("A fatal error happened when shutdown the ResourceManager", e);
		}
	}

	/**
	 * Gets the leader session id of current resourceManager.
	 *
	 * @return return the leaderSessionId of current resourceManager, this returns null until the current resourceManager is granted leadership.
	 */
	@VisibleForTesting
	UUID getLeaderSessionID() {
		return this.leaderSessionID;
	}

	/**
	 * Register a {@link JobMaster} at the resource manager.
	 *
	 * @param resourceManagerLeaderId The fencing token for the ResourceManager leader
	 * @param jobMasterAddress        The address of the JobMaster that registers
	 * @param jobID                   The Job ID of the JobMaster that registers
	 * @return Future registration response
	 */
	@RpcMethod
	public Future<RegistrationResponse> registerJobMaster(
		final UUID resourceManagerLeaderId, final UUID jobMasterLeaderId,
		final String jobMasterAddress, final JobID jobID) {

		checkNotNull(resourceManagerLeaderId);
		checkNotNull(jobMasterAddress);
		checkNotNull(jobID);

		// TODO mxm The leader retrieval needs to be split up in an async part which runs outside the main execution thread
		// The state updates should be performed inside the main thread

		final FlinkCompletableFuture<RegistrationResponse> future = new FlinkCompletableFuture<>();

		if(!leaderSessionID.equals(resourceManagerLeaderId)) {
			log.warn("Discard registration from JobMaster {} at ({}) because the expected leader session ID {}" +
					" did not equal the received leader session ID  {}",
				jobID, jobMasterAddress, leaderSessionID, resourceManagerLeaderId);
			future.complete(new RegistrationResponse.Decline("Invalid leader session id"));
			return future;
		}

		final LeaderConnectionInfo jobMasterLeaderInfo;
		try {
			jobMasterLeaderInfo = LeaderRetrievalUtils.retrieveLeaderConnectionInfo(
				highAvailabilityServices.getJobMasterLeaderRetriever(jobID), new FiniteDuration(5, TimeUnit.SECONDS));
		} catch (Exception e) {
			LOG.warn("Failed to start JobMasterLeaderRetriever for JobID {}", jobID);
			future.complete(new RegistrationResponse.Decline("Failed to retrieve JobMasterLeaderRetriever"));
			return future;
		}

		if (!jobMasterLeaderId.equals(jobMasterLeaderInfo.getLeaderSessionID())) {
			LOG.info("Declining registration request from non-leading JobManager {}", jobMasterAddress);
			future.complete(new RegistrationResponse.Decline("JobManager is not leading"));
			return future;
		}

		Future<JobMasterGateway> jobMasterGatewayFuture =
			getRpcService().connect(jobMasterAddress, JobMasterGateway.class);

		return jobMasterGatewayFuture.thenApplyAsync(new ApplyFunction<JobMasterGateway, RegistrationResponse>() {
			@Override
			public RegistrationResponse apply(JobMasterGateway jobMasterGateway) {

				final JobMasterLeaderListener jobMasterLeaderListener = new JobMasterLeaderListener(jobID);
				try {
					LeaderRetrievalService jobMasterLeaderRetriever = highAvailabilityServices.getJobMasterLeaderRetriever(jobID);
					jobMasterLeaderRetriever.start(jobMasterLeaderListener);
				} catch (Exception e) {
					LOG.warn("Failed to start JobMasterLeaderRetriever for JobID {}", jobID);
					return new RegistrationResponse.Decline("Failed to retrieve JobMasterLeaderRetriever");
				}
				jobMasterLeaderRetrievalListeners.add(jobMasterLeaderListener);
				final JobMasterGateway existingGateway = jobMasterGateways.put(jobID, jobMasterGateway);
				if (existingGateway != null) {
					log.info("Replacing gateway for registered JobID {}.", jobID);
				}
				return new JobMasterRegistrationSuccess(5000);
			}
		}, getMainThreadExecutor());
	}

	/**
	 * Requests a slot from the resource manager.
	 *
	 * @param slotRequest Slot request
	 * @return Slot assignment
	 */
	@RpcMethod
	public SlotRequestReply requestSlot(SlotRequest slotRequest) {
		final JobID jobId = slotRequest.getJobId();
		final JobMasterGateway jobMasterGateway = jobMasterGateways.get(jobId);

		if (jobMasterGateway != null) {
			return slotManager.requestSlot(slotRequest);
		} else {
			LOG.info("Ignoring slot request for unknown JobMaster with JobID {}", jobId);
			return new SlotRequestRejected(slotRequest.getAllocationId());
		}
	}


	/**
	 * @param resourceManagerLeaderId The fencing token for the ResourceManager leader
	 * @param taskExecutorAddress     The address of the TaskExecutor that registers
	 * @param resourceID              The resource ID of the TaskExecutor that registers
	 * @return The response by the ResourceManager.
	 */
	@RpcMethod
	public RegistrationResponse registerTaskExecutor(
		UUID resourceManagerLeaderId,
		String taskExecutorAddress,
		ResourceID resourceID) {

		return new TaskExecutorRegistrationSuccess(new InstanceID(), 5000);
	}


	// ------------------------------------------------------------------------
	//  Leader Contender
	// ------------------------------------------------------------------------

	/**
	 * Callback method when current resourceManager is granted leadership
	 *
	 * @param leaderSessionID unique leadershipID
	 */
	@Override
	public void grantLeadership(final UUID leaderSessionID) {
		runAsync(new Runnable() {
			@Override
			public void run() {
				log.info("ResourceManager {} was granted leadership with leader session ID {}", getAddress(), leaderSessionID);
				// confirming the leader session ID might be blocking,
				leaderElectionService.confirmLeaderSessionID(leaderSessionID);
				// notify SlotManager
				slotManager.setLeaderUUID(leaderSessionID);
				ResourceManager.this.leaderSessionID = leaderSessionID;
			}
		});
	}

	/**
	 * Callback method when current resourceManager lose leadership.
	 */
	@Override
	public void revokeLeadership() {
		runAsync(new Runnable() {
			@Override
			public void run() {
				log.info("ResourceManager {} was revoked leadership.", getAddress());
				jobMasterGateways.clear();
				slotManager.clearState();
				leaderSessionID = null;
			}
		});
	}

	/**
	 * Handles error occurring in the leader election service
	 *
	 * @param exception Exception being thrown in the leader election service
	 */
	@Override
	public void handleError(final Exception exception) {
		log.error("ResourceManager received an error from the LeaderElectionService.", exception);
		// terminate ResourceManager in case of an error
		shutDown();
	}

	private static class JobMasterLeaderListener implements LeaderRetrievalListener {

		private final JobID jobID;
		private UUID leaderID;

		private JobMasterLeaderListener(JobID jobID) {
			this.jobID = jobID;
		}

		@Override
		public void notifyLeaderAddress(final String leaderAddress, final UUID leaderSessionID) {
			this.leaderID = leaderSessionID;
		}

		@Override
		public void handleError(final Exception exception) {
			// TODO
		}
	}
}
