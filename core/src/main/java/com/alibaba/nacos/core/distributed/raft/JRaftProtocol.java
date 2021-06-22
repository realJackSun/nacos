/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
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

package com.alibaba.nacos.core.distributed.raft;

import com.alibaba.nacos.common.model.RestResult;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.utils.MapUtil;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.common.utils.ThreadUtils;
import com.alibaba.nacos.consistency.ProtocolMetaData;
import com.alibaba.nacos.consistency.SerializeFactory;
import com.alibaba.nacos.consistency.Serializer;
import com.alibaba.nacos.consistency.cp.CPProtocol;
import com.alibaba.nacos.consistency.cp.RequestProcessor4CP;
import com.alibaba.nacos.consistency.cp.MetadataKey;
import com.alibaba.nacos.consistency.entity.ReadRequest;
import com.alibaba.nacos.consistency.entity.Response;
import com.alibaba.nacos.consistency.entity.WriteRequest;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.NodeState;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.core.distributed.AbstractConsistencyProtocol;
import com.alibaba.nacos.core.distributed.raft.exception.NoSuchRaftGroupException;
import com.alibaba.nacos.core.utils.Loggers;
import com.alipay.sofa.jraft.Node;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A concrete implementation of CP protocol: JRaft.
 *
 * <pre>
 *                                           ┌──────────────────────┐
 *            ┌──────────────────────┐       │                      ▼
 *            │   ProtocolManager    │       │        ┌───────────────────────────┐
 *            └──────────────────────┘       │        │for p in [LogProcessor4CP] │
 *                        │                  │        └───────────────────────────┘
 *                        ▼                  │                      │
 *      ┌──────────────────────────────────┐ │                      ▼
 *      │    discovery LogProcessor4CP     │ │             ┌─────────────────┐
 *      └──────────────────────────────────┘ │             │  get p.group()  │
 *                        │                  │             └─────────────────┘
 *                        ▼                  │                      │
 *                 ┌─────────────┐           │                      │
 *                 │ RaftConfig  │           │                      ▼
 *                 └─────────────┘           │      ┌──────────────────────────────┐
 *                        │                  │      │  create raft group service   │
 *                        ▼                  │      └──────────────────────────────┘
 *              ┌──────────────────┐         │
 *              │  JRaftProtocol   │         │
 *              └──────────────────┘         │
 *                        │                  │
 *                     init()                │
 *                        │                  │
 *                        ▼                  │
 *               ┌─────────────────┐         │
 *               │   JRaftServer   │         │
 *               └─────────────────┘         │
 *                        │                  │
 *                        │                  │
 *                        ▼                  │
 *             ┌────────────────────┐        │
 *             │JRaftServer.start() │        │
 *             └────────────────────┘        │
 *                        │                  │
 *                        └──────────────────┘
 * </pre>
 *
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
@SuppressWarnings("all")
public class JRaftProtocol extends AbstractConsistencyProtocol<RaftConfig, RequestProcessor4CP>
        implements CPProtocol<RaftConfig, RequestProcessor4CP> {
    
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    private final AtomicBoolean shutdowned = new AtomicBoolean(false);
    
    private final Serializer serializer = SerializeFactory.getDefault();
    
    private RaftConfig raftConfig;
    
    private JRaftServer raftServer;
    
    private JRaftMaintainService jRaftMaintainService;
    
    private ServerMemberManager memberManager;
    
    public JRaftProtocol(ServerMemberManager memberManager) throws Exception {
        this.memberManager = memberManager;
        this.raftServer = new JRaftServer();
        this.jRaftMaintainService = new JRaftMaintainService(raftServer);
    }
    
    @Override
    public void init(RaftConfig config) {
        if (initialized.compareAndSet(false, true)) {
            this.raftConfig = config;
            NotifyCenter.registerToSharePublisher(RaftEvent.class);
            this.raftServer.init(this.raftConfig);
            this.raftServer.start();
            
            // There is only one consumer to ensure that the internal consumption
            // is sequential and there is no concurrent competition
            NotifyCenter.registerSubscriber(new Subscriber<RaftEvent>() {
                @Override
                public void onEvent(RaftEvent event) {
                    Loggers.RAFT.info("This Raft event changes : {}", event);
                    final String groupId = event.getGroupId();
                    Map<String, Map<String, Object>> value = new HashMap<>();
                    Map<String, Object> properties = new HashMap<>();
                    final String leader = event.getLeader();
                    final Long term = event.getTerm();
                    final List<String> raftClusterInfo = event.getRaftClusterInfo();
                    final String errMsg = event.getErrMsg();
                    
                    // Leader information needs to be selectively updated. If it is valid data,
                    // the information in the protocol metadata is updated.
                    MapUtil.putIfValNoEmpty(properties, MetadataKey.LEADER_META_DATA, leader);
                    MapUtil.putIfValNoNull(properties, MetadataKey.TERM_META_DATA, term);
                    MapUtil.putIfValNoEmpty(properties, MetadataKey.RAFT_GROUP_MEMBER, raftClusterInfo);
                    MapUtil.putIfValNoEmpty(properties, MetadataKey.ERR_MSG, errMsg);
                    
                    value.put(groupId, properties);
                    metaData.load(value);
                    
                    // The metadata information is injected into the metadata information of the node
                    Set<Member> newMembers = parseMembers(event);
                    injectProtocolMetaData(newMembers, metaData);
                }
                
                @Override
                public Class<? extends Event> subscribeType() {
                    return RaftEvent.class;
                }
                
            });
        }
    }
    
    private Set<Member> parseMembers(RaftEvent raftEvent) {
        Set<Member> members = new ConcurrentHashSet<>();
        List<String> clusterInfo = raftEvent.getRaftClusterInfo();
        for (String memberInfo : clusterInfo) {
            String[] serverAddrArr = memberInfo.split(":");
            String ip;
            int port;
            if (serverAddrArr.length == 1) {
                ip = serverAddrArr[0];
                port = 8848;
            } else if (serverAddrArr.length == 2) {
                ip = serverAddrArr[0];
                port = Integer.parseInt(serverAddrArr[1]) + 1000;
            } else {
                continue;
            }
            members.add(Member.builder().ip(ip).port(port).state(NodeState.SUSPICIOUS).build());
        }
        return members;
    }
    
    @Override
    public void addRequestProcessors(Collection<RequestProcessor4CP> processors) {
        raftServer.createMultiRaftGroup(processors);
    }
    
    @Override
    public Response getData(ReadRequest request) throws Exception {
        CompletableFuture<Response> future = aGetData(request);
        return future.get(5_000L, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public CompletableFuture<Response> aGetData(ReadRequest request) {
        return raftServer.get(request);
    }
    
    @Override
    public Response write(WriteRequest request) throws Exception {
        CompletableFuture<Response> future = writeAsync(request);
        // Here you wait for 10 seconds, as long as possible, for the request to complete
        return future.get(10_000L, TimeUnit.MILLISECONDS);
    }
    
    @Override
    public CompletableFuture<Response> writeAsync(WriteRequest request) {
        return raftServer.commit(request.getGroup(), request, new CompletableFuture<>());
    }
    
    @Override
    public void memberChange(Set<String> addresses) {
        for (int i = 0; i < 5; i++) {
            if (this.raftServer.peerChange(jRaftMaintainService, addresses)) {
                return;
            }
            ThreadUtils.sleep(100L);
        }
        Loggers.RAFT.warn("peer removal failed");
    }
    
    @Override
    public void shutdown() {
        if (initialized.get() && shutdowned.compareAndSet(false, true)) {
            Loggers.RAFT.info("shutdown jraft server");
            raftServer.shutdown();
        }
    }
    
    @Override
    public RestResult<String> execute(Map<String, String> args) {
        return jRaftMaintainService.execute(args);
    }
    
    
    /**
     * The Method update the ServerMemberManager with the new jRaft members.
     * @param newMembers It contains all of the new members we want to inject into ServerMemberManager.
     * @param metaData The member metadata that should be injected into each of the new members.
     */
    private void injectProtocolMetaData(Set<Member> newMembers, ProtocolMetaData metaData) {
        Collection<Member> oldMembers = memberManager.allMembers();
        for (Member oldMember : oldMembers) {
            // Update the status
            if (newMembers.contains(oldMember)) {
                newMembers.remove(oldMember);
                newMembers.add(oldMember);
            }
        }
        // Here we want to find all of the members that should be removed.
        // The operation is -- add oldMembers first, and then remove newMembers, then the oldMembers - newMembers set is generated.
        Set<Member> removeMembers = new ConcurrentHashSet<>();
        removeMembers.addAll(oldMembers);
        for (Member member : newMembers) {
            if (removeMembers.contains(member)) {
                removeMembers.remove(member);
            }
        }
        // Here we want to find all of the members that should be added.
        // The operation is on the contrary -- add newMembers first, and then remove oldMembers,
        // then the newMembers - oldMembers set is generated.
        Set<Member> addMembers = new ConcurrentHashSet<>();
        addMembers.addAll(newMembers);
        for (Member member : oldMembers) {
            if (addMembers.contains(member)) {
                addMembers.remove(member);
            }
        }
        
        // We only need to inject the metadata for all new members.
        for (Member member : newMembers) {
            member.setExtendVal("raftMetaData", metaData);
        }
        memberManager.memberLeave(removeMembers);
        memberManager.memberJoin(addMembers);
    }
    
    @Override
    public boolean isLeader(String group) {
        Node node = raftServer.findNodeByGroup(group);
        if (node == null) {
            throw new NoSuchRaftGroupException(group);
        }
        return node.isLeader();
    }
}
