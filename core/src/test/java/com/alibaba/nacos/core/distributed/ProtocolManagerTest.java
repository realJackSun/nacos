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

package com.alibaba.nacos.core.distributed;

import com.alibaba.nacos.consistency.ap.APProtocol;
import com.alibaba.nacos.consistency.cp.CPProtocol;
import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.MembersChangeEvent;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.core.distributed.raft.JRaftProtocol;
import com.alibaba.nacos.sys.env.EnvUtil;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import junit.framework.TestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ProtocolManagerTest extends TestCase {
    
    @Mock
    private ConfigurableApplicationContext context;
    
    @Spy
    private CPProtocol cpProtocol;
    
    @Mock
    private APProtocol apProtocol;
    
    @Mock
    private ServerMemberManager memberManager;
    
    private ProtocolManager protocolManager;
    
    @Before
    public void setUp() throws Exception {
        cpProtocol = new JRaftProtocol(memberManager);
        when(cpProtocol.isLeader("hello")).thenReturn(true);
        doAnswer(invocationOnMock -> {
            return null;
        }).when(apProtocol).memberChange(any(Set.class));
        when(context.getBean(APProtocol.class)).thenReturn(apProtocol);
        when(context.getBean(CPProtocol.class)).thenReturn(cpProtocol);
        ApplicationUtils.injectContext(context);
        protocolManager = new ProtocolManager(memberManager);
        
    }
    
    @After
    public void tearDown() {
    }
    
    @Test
    public void testOnEvent() {
        EnvUtil.setEnvironment(new StandardEnvironment());
        String ip1 = "1.1.1.1";
        String ip2 = "2.2.2.2";
        String ip3 = "3.3.3.3";
        int port = 8848;
        Member member1 = Member.builder().ip(ip1).port(port).build();
        Member member2 = Member.builder().ip(ip2).port(port).build();
        Member member3 = Member.builder().ip(ip3).port(port).build();
        Collection<Member> members = new LinkedList<>();
        members.add(member1);
        members.add(member2);
        members.add(member3);
        MembersChangeEvent event = MembersChangeEvent.builder().members(members).build();
        protocolManager.onEvent(event);
        Set<String> ips = new HashSet<>();
        verify(cpProtocol).memberChange(eq(ips));
    }
}