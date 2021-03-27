/*
 * Copyright (C) 2019 The Turms Project
 * https://github.com/turms-im/turms
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.turms.server.common.cluster.service.discovery;

import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import im.turms.server.common.cluster.node.NodeType;
import im.turms.server.common.cluster.node.NodeVersion;
import im.turms.server.common.cluster.service.ClusterService;
import im.turms.server.common.cluster.service.config.ChangeStreamUtil;
import im.turms.server.common.cluster.service.config.SharedConfigService;
import im.turms.server.common.cluster.service.config.domain.discovery.Leader;
import im.turms.server.common.cluster.service.config.domain.discovery.Member;
import im.turms.server.common.constant.TurmsStatusCode;
import im.turms.server.common.exception.TurmsBusinessException;
import im.turms.server.common.manager.address.BaseServiceAddressManager;
import im.turms.server.common.mongo.operation.option.Filter;
import im.turms.server.common.mongo.operation.option.Update;
import im.turms.server.common.property.env.common.cluster.DiscoveryProperties;
import im.turms.server.common.util.CollectorUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.rsocket.RSocket;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.bson.BsonValue;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author James Chen
 */
@Log4j2
public class DiscoveryService implements ClusterService {

    private static final Duration CRUD_TIMEOUT_DURATION = Duration.ofSeconds(10);
    private static final Comparator<Member> MEMBER_PRIORITY_COMPARATOR = DiscoveryService::compareMemberPriority;

    @Getter
    private final ScheduledExecutorService scheduler =
            new ScheduledThreadPoolExecutor(1, new DefaultThreadFactory("turms-cluster-discovery"));
    private ScheduledFuture<?> notifyMembersChangeFuture;

    private final DiscoveryProperties discoveryProperties;

    private final SharedConfigService sharedConfigService;
    @Getter
    private final LocalNodeStatusManager localNodeStatusManager;
    @Getter
    private final ConnectionManager connectionManager;

    /**
     * Don't use volatile for better performance
     */
    @Getter
    @Nullable
    private Leader leader;

    /**
     * Use independent collections to speed up query operations
     */
    @Getter
    private final Map<String, Member> allKnownMembers = new HashMap<>();

    @Getter
    private List<Member> activeSortedServiceMemberList = new ArrayList<>();

    @Getter
    private List<Member> activeSortedGatewayMemberList = new ArrayList<>();

    /**
     * Used for RpcService so that it doesn't need to find a socket from a map by a member ID every time
     */
    @Getter
    private List<MemberInfoWithConnection> otherActiveConnectedServiceMemberList = Collections.emptyList();
    @Getter
    private List<MemberInfoWithConnection> otherActiveConnectedGatewayMemberList = Collections.emptyList();

    private final List<MembersChangeListener> membersChangeListeners = new LinkedList<>();

    public DiscoveryService(
            String clusterId,
            String nodeId,
            NodeType nodeType,
            NodeVersion nodeVersion,
            boolean isLeaderEligible,
            boolean isActive,
            int memberBindPort,
            DiscoveryProperties discoveryProperties,
            BaseServiceAddressManager serviceAddressManager,
            SharedConfigService sharedConfigService) {
        Date now = new Date();
        Member localMember = new Member(clusterId,
                nodeId,
                nodeType,
                nodeVersion,
                false,
                isLeaderEligible,
                now,
                (int) now.getTime(),
                serviceAddressManager.getMemberHost(),
                memberBindPort,
                serviceAddressManager.getMetricsApiAddress(),
                serviceAddressManager.getAdminApiAddress(),
                serviceAddressManager.getWsAddress(),
                serviceAddressManager.getTcpAddress(),
                serviceAddressManager.getUdpAddress(),
                false,
                isActive);
        this.discoveryProperties = discoveryProperties;
        this.sharedConfigService = sharedConfigService;
        this.localNodeStatusManager = new LocalNodeStatusManager(
                this,
                sharedConfigService,
                localMember,
                discoveryProperties.getHeartbeatTimeoutInSeconds(),
                discoveryProperties.getHeartbeatIntervalInSeconds());
        serviceAddressManager.addOnAddressesChangedListener(addresses -> {
            String nodeHost = addresses.getMemberHost();
            String metricsApiAddress = addresses.getMetricsApiAddress();
            String adminApiAddress = addresses.getAdminApiAddress();
            String wsAddress = addresses.getWsAddress();
            String tcpAddress = addresses.getTcpAddress();
            String udpAddress = addresses.getUdpAddress();
            Update update = Update.newBuilder()
                    .setIfNotNull(Member.Fields.memberHost, nodeHost)
                    .setIfNotNull(Member.Fields.metricsApiAddress, metricsApiAddress)
                    .setIfNotNull(Member.Fields.adminApiAddress, adminApiAddress)
                    .setIfNotNull(Member.Fields.wsAddress, wsAddress)
                    .setIfNotNull(Member.Fields.tcpAddress, tcpAddress)
                    .setIfNotNull(Member.Fields.udpAddress, udpAddress);
            localNodeStatusManager.upsertLocalNodeInfo(update).subscribe();
        });
        this.connectionManager = new ConnectionManager(this, discoveryProperties);
        connectionManager.addMemberConnectionChangeListener(new MemberConnectionChangeListener() {
            @Override
            public void onMemberConnectionAdded(Member member, RSocket connection) {
                updateOtherActiveConnectedMemberList(true, member, connection);
            }

            @Override
            public void onMemberConnectionRemoved(Member member) {
                updateOtherActiveConnectedMemberList(false, member, null);
            }
        });
    }

    private static int compareMemberPriority(Member m1, Member m2) {
        int m1Priority = m1.getPriority();
        int m2Priority = m2.getPriority();
        if (m1Priority == m2Priority) {
            // Don't use 0 to make sure that the order is consistent in every nodes
            // and it should never happen
            return m1.getNodeId().hashCode() < m2.getNodeId().hashCode() ? -1 : 1;
        } else {
            return m1Priority < m2Priority ? -1 : 1;
        }
    }

    @Override
    public void start() {
        listenLeadershipChangeEvent();

        // Members
        listenMembersChangeEvent();
        List<Member> memberList = queryMembers()
                .collect(CollectorUtil.toList())
                .block(CRUD_TIMEOUT_DURATION);
        Member localMember = localNodeStatusManager.getLocalMember();
        for (Member member : memberList) {
            if (localMember.isSameNode(member)) {
                String message = "Failed to bootstrap the local node because the local node has been registered: "
                        + "[Local Node]: " + localMember + ", "
                        + "[Registered Node]" + member;
                throw new IllegalStateException(message);
            }
            onMemberAddedOrReplaced(member);
        }
        onMemberAddedOrReplaced(localMember);
        updateActiveMembers(allKnownMembers.values());

        localNodeStatusManager.registerLocalMember().block(CRUD_TIMEOUT_DURATION);
        localNodeStatusManager.tryBecomeFirstLeader().block();
        localNodeStatusManager.startHeartbeat();
    }

    private Flux<Member> queryMembers() {
        Filter filter = Filter.newBuilder()
                .eq(Member.ID_CLUSTER_ID, localNodeStatusManager.getLocalMember().getClusterId());
        return sharedConfigService.find(Member.class, filter);
    }

    private void listenLeadershipChangeEvent() {
        sharedConfigService.subscribe(Leader.class, FullDocument.UPDATE_LOOKUP)
                .doOnNext(event -> {
                    Leader changedLeader = event.getFullDocument();
                    String clusterId = changedLeader != null
                            ? changedLeader.getClusterId()
                            : ChangeStreamUtil.getIdAsString(event.getDocumentKey());
                    if (clusterId.equals(localNodeStatusManager.getLocalMember().getClusterId())) {
                        switch (event.getOperationType()) {
                            case INSERT:
                            case REPLACE:
                            case UPDATE:
                                leader = changedLeader;
                                break;
                            case INVALIDATE:
                                leader = null;
                                int delay = (int) (5 * ThreadLocalRandom.current().nextFloat());
                                Mono.delay(Duration.ofSeconds(delay))
                                        .subscribe(ignored -> {
                                            if (leader == null) {
                                                localNodeStatusManager.tryBecomeFirstLeader().subscribe();
                                            }
                                        });
                                break;
                        }
                    }
                })
                .onErrorContinue((throwable, o) -> log.error("Error while processing the change stream event of Leader: {}", o, throwable))
                .subscribe();
    }

    private void listenMembersChangeEvent() {
        // Because the information of members changes frequently due to lastHeartbeatDate,
        // use DEFAULT instead of UPDATE_LOOKUP to reduce the overhead of data
        sharedConfigService.subscribe(Member.class, FullDocument.DEFAULT)
                .doOnNext(event -> {
                    Member changedMember = event.getFullDocument();
                    String clusterId = changedMember != null
                            ? changedMember.getClusterId()
                            : ChangeStreamUtil.getStringFromId(event.getDocumentKey(), Member.Key.Fields.clusterId);
                    String nodeId = ChangeStreamUtil.getStringFromId(event.getDocumentKey(), Member.Key.Fields.nodeId);
                    if (clusterId.equals(localNodeStatusManager.getLocalMember().getClusterId())) {
                        switch (event.getOperationType()) {
                            case INSERT:
                            case REPLACE:
                                onMemberAddedOrReplaced(changedMember);
                                break;
                            case UPDATE:
                                onMemberUpdated(nodeId, event.getUpdateDescription());
                                break;
                            case DELETE:
                                Member deletedMember = allKnownMembers.remove(nodeId);
                                updateOtherActiveConnectedMemberList(false, deletedMember, null);
                                // Note that we assume that there is no the case:
                                // a node isn't shutdown but has just been unregistered in the registry
                                // because the node may lose the connection with the registry and TTL has passed.
                                // During the time, another node with the SAME node ID registers itself.
                                // If the lost node recovers again, there is a potential bug.
                                if (nodeId.equals(localNodeStatusManager.getLocalMember().getNodeId())) {
                                    localNodeStatusManager.setLocalNodeRegistered(false);
                                    if (!localNodeStatusManager.isClosing()) {
                                        localNodeStatusManager.registerLocalMember()
                                                // Ignore the error because the node may has been registered by its heartbeat timer
                                                .onErrorResume(DuplicateKeyException.class, e -> Mono.empty())
                                                .subscribe();
                                    }
                                }
                                break;
                        }
                        updateActiveMembers(allKnownMembers.values());
                        connectionManager.updateHasConnectedToAllMembers(allKnownMembers.keySet());
                    }
                })
                .onErrorContinue((throwable, o) -> log.error("Error while processing the change stream event of Member: {}", o, throwable))
                .subscribe();
    }

    private void onMemberUpdated(String nodeId, UpdateDescription updateDescription) {
        // TODO: edge case for member not found
        Member memberToUpdate = allKnownMembers.get(nodeId);
        // Status
        Boolean hasJoinedCluster = null;
        Boolean isActive = null;
        Date lastHeartbeatDate = null;
        // Info
        Boolean isSeed = null;
        Boolean isLeaderEligible = null;
        Integer priority = null;
        String memberHost = null;
        String metricsApiAddress = null;
        String adminApiAddress = null;
        String wsAddress = null;
        String tcpAddress = null;
        String udpAddress = null;
        Set<Map.Entry<String, BsonValue>> entries = updateDescription.getUpdatedFields().entrySet();
        for (Map.Entry<String, BsonValue> entry : entries) {
            String fieldName = entry.getKey();
            BsonValue value = entry.getValue();
            // Check status change
            if (fieldName.endsWith(Member.MemberStatus.Fields.lastHeartbeatDate)) {
                lastHeartbeatDate = new Date(value.asDateTime().getValue());
                continue;
            }
            if (fieldName.endsWith(Member.MemberStatus.Fields.isHealthy)) {
                hasJoinedCluster = value.asBoolean().getValue();
                continue;
            }
            if (fieldName.endsWith(Member.MemberStatus.Fields.isActive)) {
                isActive = value.asBoolean().getValue();
                continue;
            }
            // Check info
            if (fieldName.equals(Member.Fields.isSeed)) {
                isSeed = value.asBoolean().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.isLeaderEligible)) {
                isLeaderEligible = value.asBoolean().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.priority)) {
                priority = value.asInt32().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.memberHost)) {
                memberHost = value.asString().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.metricsApiAddress)) {
                metricsApiAddress = value.asString().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.adminApiAddress)) {
                adminApiAddress = value.asString().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.wsAddress)) {
                wsAddress = value.asString().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.tcpAddress)) {
                tcpAddress = value.asString().getValue();
                continue;
            }
            if (fieldName.equals(Member.Fields.udpAddress)) {
                udpAddress = value.asString().getValue();
            }
        }
        memberToUpdate.updateIfNotNull(
                isSeed,
                isLeaderEligible,
                priority,
                memberHost,
                metricsApiAddress,
                adminApiAddress,
                wsAddress,
                tcpAddress,
                udpAddress,
                hasJoinedCluster,
                isActive,
                lastHeartbeatDate);
    }

    private void onMemberAddedOrReplaced(Member newMember) {
        synchronized (this) {
            String nodeId = newMember.getNodeId();
            allKnownMembers.put(nodeId, newMember);
            boolean isLocalNode = nodeId.equals(localNodeStatusManager.getLocalMember().getNodeId());
            if (isLocalNode) {
                localNodeStatusManager.updateInfo(newMember);
            }
            RSocket connection = connectionManager.getMemberConnection(newMember.getNodeId());
            if (newMember.getStatus().isActive() && connection != null) {
                updateOtherActiveConnectedMemberList(true, newMember, connection);
                if (notifyMembersChangeFuture != null) {
                    notifyMembersChangeFuture.cancel(false);
                }
                notifyMembersChangeFuture = scheduler.schedule(
                        this::notifyMembersChangeListeners,
                        discoveryProperties.getJitterInSeconds(),
                        TimeUnit.SECONDS);
            }
        }
        connectionManager.connectMemberUntilSucceedOrRemoved(newMember);
    }

    private synchronized void updateActiveMembers(Collection<Member> allKnownMembers) {
        List<Member> list = new ArrayList<>(allKnownMembers);
        list.sort(MEMBER_PRIORITY_COMPARATOR);
        int size = list.size();
        List<Member> tempActiveSortedServiceMemberList = new ArrayList<>(size);
        List<Member> tempActiveSortedGatewayMemberList = new ArrayList<>(size);
        for (Member member : list) {
            if (member.getStatus().isActive()) {
                if (member.getNodeType() == NodeType.SERVICE) {
                    tempActiveSortedServiceMemberList.add(member);
                } else {
                    tempActiveSortedGatewayMemberList.add(member);
                }
            }
        }
        activeSortedServiceMemberList = tempActiveSortedServiceMemberList;
        activeSortedGatewayMemberList = tempActiveSortedGatewayMemberList;
    }

    private synchronized void updateOtherActiveConnectedMemberList(boolean isAdd, Member member, RSocket connection) {
        boolean isLocalNode = member.isSameNode(localNodeStatusManager.getLocalMember());
        if (isLocalNode) {
            return;
        }
        boolean isServiceMember = member.getNodeType() == NodeType.SERVICE;
        List<MemberInfoWithConnection> memberList = isServiceMember
                ? otherActiveConnectedServiceMemberList
                : otherActiveConnectedGatewayMemberList;
        int size = isAdd
                ? memberList.size() + 1
                : memberList.size();
        List<MemberInfoWithConnection> tempOtherActiveConnectedMemberList = new ArrayList<>(size);
        tempOtherActiveConnectedMemberList.addAll(memberList);
        if (isAdd) {
            tempOtherActiveConnectedMemberList.add(new MemberInfoWithConnection(member, connection));
        } else {
            tempOtherActiveConnectedMemberList.remove(new MemberInfoWithConnection(member, null));
        }
        tempOtherActiveConnectedMemberList.sort((m1, m2) -> MEMBER_PRIORITY_COMPARATOR.compare(m1.getMember(), m2.getMember()));
        if (isServiceMember) {
            otherActiveConnectedServiceMemberList = tempOtherActiveConnectedMemberList;
        } else {
            otherActiveConnectedGatewayMemberList = tempOtherActiveConnectedMemberList;
        }
    }

    /**
     * @return null if the local node isn't active
     */
    @Nullable
    public Integer getLocalServiceMemberIndex() {
        int index = activeSortedServiceMemberList.indexOf(getLocalMember());
        return index != -1 ? index : null;
    }

    @Override
    public void stop() {
        localNodeStatusManager.setClosing(true);
        scheduler.shutdownNow();
        connectionManager.stop();
        if (localNodeStatusManager.isLocalNodeRegistered()) {
            localNodeStatusManager.unregisterLocalMember().block(CRUD_TIMEOUT_DURATION);
        }
    }

    // Registration

    public Mono<Void> registerMember(Member member) {
        if (member.getClusterId() == null
                || member.getNodeId() == null) {
            throw new IllegalArgumentException("Failed to register member because required fields are missing");
        }
        return sharedConfigService.insert(member).then();
    }

    public Mono<Void> unregisterMembers(Set<String> nodeIds) {
        Filter filter = Filter.newBuilder()
                .eq(Member.ID_CLUSTER_ID, getLocalMember().getClusterId())
                .in(Member.ID_NODE_ID, nodeIds);
        return sharedConfigService.remove(Member.class, filter).then();
    }

    public Mono<Void> updateMemberInfo(@NotNull String id,
                                       @Nullable Boolean isSeed,
                                       @Nullable Boolean isLeaderEligible,
                                       @Nullable Boolean isActive,
                                       @Nullable Integer priority) {
        Member member = allKnownMembers.get(id);
        if (member == null) {
            return Mono.error(TurmsBusinessException.get(TurmsStatusCode.NO_CONTENT));
        }
        Filter filter = Filter.newBuilder()
                .eq(Member.ID_CLUSTER_ID, getLocalMember().getClusterId())
                .eq(Member.ID_NODE_ID, id);
        Update update = Update.newBuilder()
                .setIfNotNull(Member.Fields.isSeed, isSeed)
                .setIfNotNull(Member.Fields.isLeaderEligible, isLeaderEligible)
                .setIfNotNull(Member.STATUS_IS_ACTIVE, isActive)
                .setIfNotNull(Member.Fields.priority, priority);
        // Note that we just need to update the member info in the config server
        // and the listener to the change stream will do remaining jobs.
        return sharedConfigService.upsert(filter, update, member);
    }

    // Event

    public void addListenerOnMembersChange(MembersChangeListener listener) {
        membersChangeListeners.add(listener);
    }

    private void notifyMembersChangeListeners() {
        for (MembersChangeListener listener : membersChangeListeners) {
            listener.onMembersChange();
        }
    }

    //

    public Member getLocalMember() {
        return localNodeStatusManager.getLocalMember();
    }

    public boolean isKnownMember(String nodeId) {
        return allKnownMembers.containsKey(nodeId);
    }

    // Leader

    /**
     * @return members with the same highest priority
     */
    public List<Member> findQualifiedMembersToBeLeader() {
        List<Member> members = new ArrayList<>(activeSortedServiceMemberList.size());
        int highestPriority = Integer.MIN_VALUE;
        for (Member member : activeSortedServiceMemberList) {
            if (member.getPriority() < highestPriority) {
                return members;
            }
            if (isQualifiedToBeLeader(member)) {
                highestPriority = member.getPriority();
                members.add(member);
            }
        }
        return members;
    }

    /**
     * @implNote Even a member with a lower priority is qualified to be a leader
     */
    public boolean isQualifiedToBeLeader(Member member) {
        return member.getNodeType() == NodeType.SERVICE
                && member.isLeaderEligible()
                && member.getStatus().isActive();
    }

    public Mono<Member> electNewLeaderByMember(Member member) {
        String clusterId = member.getClusterId();
        String nodeId = member.getNodeId();
        if (!isQualifiedToBeLeader(member)) {
            return Mono.error(TurmsBusinessException.get(TurmsStatusCode.NOT_QUALIFIED_MEMBER_TO_BE_LEADER));
        }
        int generation = leader == null ? 1 : leader.getGeneration() + 1;
        Filter filter = Filter.newBuilder()
                .eq(Leader.Fields.clusterId, clusterId)
                .or(Filter.newBuilder().eq(Leader.Fields.generation, null),
                        Filter.newBuilder().lt(Leader.Fields.generation, generation));
        Date now = new Date();
        Update update = Update.newBuilder()
                .set(Leader.Fields.nodeId, nodeId)
                .set(Leader.Fields.renewDate, now);
        Leader localLeader = new Leader(clusterId, nodeId, now, generation);
        return sharedConfigService.upsert(filter, update, localLeader).thenReturn(member);
    }

    public Mono<Member> electNewLeaderByNodeId(String nodeId) {
        Member member = allKnownMembers.get(nodeId);
        if (member == null) {
            return Mono.error(TurmsBusinessException.get(TurmsStatusCode.NON_EXISTING_MEMBER_TO_BE_LEADER));
        }
        return electNewLeaderByMember(member);
    }

    public Mono<Member> electNewLeaderByPriority() {
        List<Member> qualifiedMembers = findQualifiedMembersToBeLeader();
        if (qualifiedMembers.isEmpty()) {
            return Mono.error(TurmsBusinessException.get(TurmsStatusCode.NO_QUALIFIED_MEMBER_TO_BE_LEADER));
        }
        if (leader != null) {
            for (Member qualifiedMember : qualifiedMembers) {
                if (qualifiedMember.getNodeId().equals(leader.getNodeId())) {
                    return Mono.just(qualifiedMember);
                }
            }
        }
        return electNewLeaderByNodeId(qualifiedMembers.get(0).getNodeId());
    }

}