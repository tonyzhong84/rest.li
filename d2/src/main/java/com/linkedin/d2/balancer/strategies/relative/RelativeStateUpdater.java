/*
   Copyright (c) 2020 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.d2.balancer.strategies.relative;

import com.linkedin.d2.D2RelativeStrategyProperties;
import com.linkedin.d2.balancer.clients.TrackerClient;
import com.linkedin.d2.balancer.strategies.PartitionStateUpdateListener;
import com.linkedin.d2.balancer.strategies.StateUpdater;
import com.linkedin.d2.balancer.strategies.degrader.DelegatingRingFactory;
import com.linkedin.d2.balancer.util.hashing.Ring;
import com.linkedin.util.degrader.CallTracker;
import com.linkedin.util.degrader.ErrorType;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Update the state of the RelativeLoadBalancerStrategy periodically
 */
public class RelativeStateUpdater implements StateUpdater
{
  private static final Logger LOG = LoggerFactory.getLogger(RelativeStateUpdater.class);
  public static final double MIN_HEALTH_SCORE = 0.0;
  public static final double MAX_HEALTH_SCORE = 1.0;
  private static final double SLOW_START_INITIAL_HEALTH_SCORE = 0.01;
  private static final int SLOW_START_RECOVERY_FACTOR = 2;
  private static final int LOG_UNHEALTHY_CLIENT_NUMBERS = 10;
  private static final long EXECUTOR_INITIAL_DELAY = 10;

  private final D2RelativeStrategyProperties _relativeStrategyProperties;
  private final QuarantineManager _quarantineManager;
  private final ScheduledExecutorService _executorService;
  private final Lock _lock;
  private final List<PartitionStateUpdateListener.Factory<PartitionState>> _listenerFactories;

  private ConcurrentMap<Integer, PartitionState> _partitionLoadBalancerStateMap;

  RelativeStateUpdater(D2RelativeStrategyProperties relativeStrategyProperties,
                       QuarantineManager quarantineManager,
                       ScheduledExecutorService executorService,
                       List<PartitionStateUpdateListener.Factory<PartitionState>> listenerFactories)
  {
    this(relativeStrategyProperties, quarantineManager, executorService, new ConcurrentHashMap<>(), listenerFactories);
  }

  RelativeStateUpdater(D2RelativeStrategyProperties relativeStrategyProperties,
      QuarantineManager quarantineManager,
      ScheduledExecutorService executorService,
      ConcurrentMap<Integer, PartitionState> partitionLoadBalancerStateMap,
      List<PartitionStateUpdateListener.Factory<PartitionState>> listenerFactories)
  {
    _relativeStrategyProperties = relativeStrategyProperties;
    _quarantineManager = quarantineManager;
    _executorService = executorService;
    _listenerFactories = listenerFactories;
    _partitionLoadBalancerStateMap = partitionLoadBalancerStateMap;
    _lock = new ReentrantLock();

    _executorService.scheduleWithFixedDelay((Runnable) this::updateState, EXECUTOR_INITIAL_DELAY,
        _relativeStrategyProperties.getUpdateIntervalMs(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void updateState(Set<TrackerClient> trackerClients, int partitionId, long clusterGenerationId)
  {
    if (!_partitionLoadBalancerStateMap.containsKey(partitionId))
    {
      // If the partition is not initialized, initialize the state synchronously
      initializePartition(trackerClients, partitionId, clusterGenerationId);
    }
    else if (clusterGenerationId != _partitionLoadBalancerStateMap.get(partitionId).getClusterGenerationId())
    {
      // Asynchronously update the state if it is from uri properties change
      PartitionState oldPartitionState = _partitionLoadBalancerStateMap.get(partitionId);
      _executorService.execute(() -> updateStateForPartition(trackerClients, partitionId, oldPartitionState, clusterGenerationId));
    }
  }

  @Override
  public Ring<URI> getRing(int partitionId)
  {
    return _partitionLoadBalancerStateMap.get(partitionId) == null
        ? null
        : _partitionLoadBalancerStateMap.get(partitionId).getRing();
  }

  Map<URI, Integer> getPointsMap(int partitionId)
  {
    return _partitionLoadBalancerStateMap.get(partitionId) == null
        ? new HashMap<>()
        : _partitionLoadBalancerStateMap.get(partitionId).getPointsMap();
  }

  /**
   * Update the partition state.
   * This is scheduled by executor, and the update should not involve any cluster change
   */
  void updateState()
  {
    // Update state for each partition
    for (Integer partitionId : _partitionLoadBalancerStateMap.keySet())
    {
      PartitionState partitionState = _partitionLoadBalancerStateMap.get(partitionId);
      if (partitionState != null)
      {
        updateStateForPartition(partitionState.getTrackerClients(), partitionId, partitionState, null);
      }
    }
  }

  /**
   * Update the partition state when there is a cluster uris change.
   */
  void updateStateForPartition(Set<TrackerClient> trackerClients, int partitionId,
      PartitionState oldPartitionState, Long clusterGenerationId)
  {
    LOG.debug("Updating for partition: " + partitionId + ", state: " + oldPartitionState);
    PartitionState newPartitionState = oldPartitionState.copy();

    // Step 1: Update the base health scores for each {@link TrackerClient} in the cluster
    Map<TrackerClient, CallTracker.CallStats> latestCallStatsMap = new HashMap<>();
    long avgClusterLatency = getAvgClusterLatency(trackerClients, latestCallStatsMap);
    updateHealthScoreAndState(trackerClients, newPartitionState, avgClusterLatency, clusterGenerationId, latestCallStatsMap);

    // Step 2: Handle quarantine and recovery for all tracker clients in this cluster
    // this will adjust the base health score if there is any change in quarantine and recovery map
    _quarantineManager.updateQuarantineState(newPartitionState,
        oldPartitionState, avgClusterLatency);

    // Step 3: Calculate the new ring for each partition
    newPartitionState.resetRing();

    if (clusterGenerationId != null)
    {
      newPartitionState.setClusterGenerationId(clusterGenerationId);
    }
    _partitionLoadBalancerStateMap.put(partitionId, newPartitionState);

    // Step 4: Log and emit monitor event
    _executorService.execute(() -> {
      logState(oldPartitionState, newPartitionState, partitionId);
      notifyPartitionStateUpdateListener(newPartitionState);
    });
  }

  /**
   * Update the health score of all tracker clients for the service
   * @param trackerClients All the tracker clients in this cluster
   */
  private void updateHealthScoreAndState(Set<TrackerClient> trackerClients,
      PartitionState partitionState, long clusterAvgLatency,
      Long clusterGenerationId, Map<TrackerClient, CallTracker.CallStats> lastCallStatsMap)
  {
    // Calculate the base health score before we override them when handling the quarantine and recovery
    calculateBaseHealthScore(trackerClients, partitionState, clusterAvgLatency, lastCallStatsMap);

    // Remove the trackerClients from original map if there is any change in uri list
    Map<TrackerClient, TrackerClientState> trackerClientStateMap = partitionState.getTrackerClientStateMap();
    if (clusterGenerationId != null)
    {
      List<TrackerClient> trackerClientsToRemove = trackerClientStateMap.keySet().stream()
          .filter(oldTrackerClient -> !trackerClients.contains(oldTrackerClient))
          .collect(Collectors.toList());
      for (TrackerClient trackerClient : trackerClientsToRemove)
      {
        partitionState.removeTrackerClient(trackerClient);
      }
    }
  }

  private void calculateBaseHealthScore(Set<TrackerClient> trackerClients, PartitionState partitionState,
      long avgClusterLatency, Map<TrackerClient, CallTracker.CallStats> lastCallStatsMap)
  {
    Map<TrackerClient, TrackerClientState> trackerClientStateMap = partitionState.getTrackerClientStateMap();

    // Update health score
    long clusterCallCount = 0;
    long clusterErrorCount = 0;
    for (TrackerClient trackerClient : trackerClients)
    {
      CallTracker.CallStats latestCallStats = lastCallStatsMap.get(trackerClient);

      if (trackerClientStateMap.containsKey(trackerClient))
      {
        TrackerClientState trackerClientState = trackerClientStateMap.get(trackerClient);
        int callCount = latestCallStats.getCallCount() + latestCallStats.getOutstandingCount();
        double errorRate = getErrorRateByType(latestCallStats.getErrorTypeCounts(), callCount);
        long avgLatency = getAvgHostLatency(latestCallStats);
        double oldHealthScore = trackerClientState.getHealthScore();
        double newHealthScore = oldHealthScore;

        clusterCallCount += callCount;
        clusterErrorCount += errorRate * callCount;

        if (isUnhealthy(trackerClientState, avgClusterLatency, callCount, avgLatency, errorRate))
        {
          // If it is above high latency, we reduce the health score by down step
          newHealthScore = Double.max(trackerClientState.getHealthScore() - _relativeStrategyProperties.getDownStep(), MIN_HEALTH_SCORE);
          trackerClientState.setHealthState(TrackerClientState.HealthState.UNHEALTHY);
        }
        else if (trackerClientState.getHealthScore() < MAX_HEALTH_SCORE
            && isHealthy(trackerClientState, avgClusterLatency, callCount, avgLatency, errorRate))
        {
          if (oldHealthScore < _relativeStrategyProperties.getSlowStartThreshold())
          {
            // If the client is healthy and slow start is enabled, we double the health score
            newHealthScore = oldHealthScore > MIN_HEALTH_SCORE
                ? Math.min(MAX_HEALTH_SCORE, SLOW_START_RECOVERY_FACTOR * oldHealthScore)
                : SLOW_START_INITIAL_HEALTH_SCORE;
          }
          else
          {
            // If slow start is not enabled, we just increase the health score by up step
            newHealthScore = Math.min(MAX_HEALTH_SCORE, oldHealthScore + _relativeStrategyProperties.getUpStep());
          }
          trackerClientState.setHealthState(TrackerClientState.HealthState.HEALTHY);
        }
        else
        {
          trackerClientState.setHealthState(TrackerClientState.HealthState.NEUTRAL);
        }
        trackerClientState.setHealthScore(newHealthScore);
        trackerClientState.setCallCount(callCount);
      }
      else
      {
        // If it is a new client, we directly set health score as the initial health score to initialize
        trackerClientStateMap.put(trackerClient, new TrackerClientState(_relativeStrategyProperties.getInitialHealthScore(),
            _relativeStrategyProperties.getMinCallCount()));
      }
    }
    partitionState.setPartitionStats(avgClusterLatency, clusterCallCount, clusterErrorCount);
  }

  private long getAvgClusterLatency(Set<TrackerClient> trackerClients, Map<TrackerClient, CallTracker.CallStats> latestCallStatsMap)
  {
    long latencySum = 0;
    long outstandingLatencySum = 0;
    int callCountSum = 0;
    int outstandingCallCountSum = 0;

    for (TrackerClient trackerClient : trackerClients)
    {
      CallTracker.CallStats latestCallStats = trackerClient.getCallTracker().getCallStats();
      latestCallStatsMap.put(trackerClient, latestCallStats);

      int callCount = latestCallStats.getCallCount();
      int outstandingCallCount = latestCallStats.getOutstandingCount();
      latencySum += latestCallStats.getCallTimeStats().getAverage() * callCount;
      outstandingLatencySum += latestCallStats.getOutstandingStartTimeAvg() * outstandingCallCount;
      callCountSum += callCount;
      outstandingCallCountSum += outstandingCallCount;
    }

    return callCountSum + outstandingCallCountSum == 0
        ? 0
        : (latencySum + outstandingLatencySum) / (callCountSum + outstandingCallCountSum);
  }

  private static long getAvgHostLatency(CallTracker.CallStats callStats)
  {
    double avgLatency = callStats.getCallTimeStats().getAverage();
    long avgOutstandingLatency = callStats.getOutstandingStartTimeAvg();
    int callCount = callStats.getCallCount();
    int outstandingCallCount = callStats.getOutstandingCount();
    return callCount + outstandingCallCount == 0
        ? 0
        : Math.round(avgLatency * ((double)callCount / (callCount + outstandingCallCount))
            + avgOutstandingLatency * ((double)outstandingCallCount / (callCount + outstandingCallCount)));
  }

  /**
   * Identify if a client is unhealthy
   */
  private boolean isUnhealthy(TrackerClientState trackerClientState, long avgClusterLatency,
      int callCount, long latency, double errorRate)
  {
    return callCount >= trackerClientState.getAdjustedMinCallCount()
        && (latency >= avgClusterLatency * _relativeStrategyProperties.getRelativeLatencyHighThresholdFactor()
        || errorRate >= _relativeStrategyProperties.getHighErrorRate());
  }

  /**
   * Identify if a client is healthy
   */
  private boolean isHealthy(TrackerClientState trackerClientState, long avgClusterLatency,
      int callCount, long latency, double errorRate)
  {
    return callCount >= trackerClientState.getAdjustedMinCallCount()
        && (latency <= avgClusterLatency * _relativeStrategyProperties.getRelativeLatencyLowThresholdFactor()
        || errorRate <= _relativeStrategyProperties.getLowErrorRate());
  }

  private void notifyPartitionStateUpdateListener(PartitionState state)
  {
    state.getListeners().forEach(listener -> listener.onUpdate(state));
  }

  private static double getErrorRateByType(Map<ErrorType, Integer> errorTypeCounts, int callCount)
  {
    Integer connectExceptionCount = errorTypeCounts.getOrDefault(ErrorType.CONNECT_EXCEPTION, 0);
    Integer closedChannelExceptionCount = errorTypeCounts.getOrDefault(ErrorType.CLOSED_CHANNEL_EXCEPTION, 0);
    Integer serverErrorCount = errorTypeCounts.getOrDefault(ErrorType.SERVER_ERROR, 0);
    Integer timeoutExceptionCount = errorTypeCounts.getOrDefault(ErrorType.TIMEOUT_EXCEPTION, 0);
    return callCount == 0
        ? 0
        : (double) (connectExceptionCount + closedChannelExceptionCount + serverErrorCount + timeoutExceptionCount) / callCount;
  }

  private void initializePartition(Set<TrackerClient> trackerClients, int partitionId, long clusterGenerationId)
  {
    PartitionState partitionState = new PartitionState(partitionId,
            new DelegatingRingFactory<>(_relativeStrategyProperties.getRingProperties()),
            _relativeStrategyProperties.getRingProperties().getPointsPerWeight(),
            _listenerFactories.stream().map(factory -> factory.create(partitionId)).collect(Collectors.toList()));

    _lock.lock();
    try
    {
      if (!_partitionLoadBalancerStateMap.containsKey(partitionId))
      {
        updateStateForPartition(trackerClients, partitionId, partitionState, clusterGenerationId);
      }
    }
    finally
    {
      _lock.unlock();
    }
  }

  private static void logState(PartitionState oldState,
      PartitionState newState,
      int partitionId)
  {
    Map<TrackerClient, TrackerClientState> newTrackerClientStateMap = newState.getTrackerClientStateMap();
    Map<TrackerClient, TrackerClientState> oldTrackerClientStateMap = oldState.getTrackerClientStateMap();
    Set<TrackerClient> newUnhealthyClients = newTrackerClientStateMap.keySet().stream()
        .filter(trackerClient -> newTrackerClientStateMap.get(trackerClient).getHealthScore() < MAX_HEALTH_SCORE)
        .collect(Collectors.toSet());
    Set<TrackerClient> oldUnhealthyClients = oldTrackerClientStateMap.keySet().stream()
        .filter(trackerClient -> oldTrackerClientStateMap.get(trackerClient).getHealthScore() < MAX_HEALTH_SCORE)
        .collect(Collectors.toSet());

    if (LOG.isDebugEnabled())
    {
      LOG.debug("Strategy updated: partitionId= " + partitionId + ", newState=" + newState + ", unhealthyClients = ["
          + (newUnhealthyClients.stream().map(client -> getClientStats(client, newTrackerClientStateMap))
          .collect(Collectors.joining(","))) + "]");
    }
    else if (allowToLog(oldState, newState, newUnhealthyClients, oldUnhealthyClients))
    {
      LOG.info("Strategy updated: partitionId= " + partitionId + ", newState=" + newState + ", unhealthyClients = ["
          + (newUnhealthyClients.stream().limit(LOG_UNHEALTHY_CLIENT_NUMBERS)
          .map(client -> getClientStats(client, newTrackerClientStateMap)).collect(Collectors.joining(",")))
          + (newUnhealthyClients.size() > LOG_UNHEALTHY_CLIENT_NUMBERS ? "...(total "
          + newUnhealthyClients.size() + ")" : "") + "]");
    }
  }

  private static boolean allowToLog(PartitionState oldState, PartitionState newState,
      Set<TrackerClient> newUnhealthyClients, Set<TrackerClient> oldUnhealthyClients)
  {
    for (URI uri : newState.getPointsMap().keySet())
    {
      if (!oldState.getPointsMap().containsKey(uri))
      {
        return true;
      }
    }

    for (TrackerClient client : newUnhealthyClients)
    {
      if (!oldUnhealthyClients.contains(client))
      {
        return true;
      }
    }

    for (TrackerClient trackerClient : newState.getRecoveryTrackerClients())
    {
      if (!oldState.getRecoveryTrackerClients().contains(trackerClient))
      {
        return true;
      }
    }

    for (TrackerClient trackerClient : newState.getQuarantineMap().keySet())
    {
      if (!oldState.getQuarantineMap().containsKey(trackerClient))
      {
        return true;
      }
    }
    return false;
  }

  private static String getClientStats(TrackerClient client, Map<TrackerClient, TrackerClientState> trackerClientStateMap)
  {
    return client.getUri() + ":" + trackerClientStateMap.get(client).getHealthScore();
  }
}
