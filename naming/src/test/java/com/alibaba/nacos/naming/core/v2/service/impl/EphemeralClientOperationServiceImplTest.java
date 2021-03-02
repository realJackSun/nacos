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

package com.alibaba.nacos.naming.core.v2.service.impl;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.naming.core.v2.client.AbstractClient;
import com.alibaba.nacos.naming.core.v2.client.impl.IpPortBasedClient;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManagerDelegate;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.pojo.Subscriber;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class EphemeralClientOperationServiceImplTest {
    
    private EphemeralClientOperationServiceImpl ephemeralClientOperationService;
    
    @Mock
    private ClientManagerDelegate clientManagerDelegate;
    
    private AbstractClient client;
    
    @Mock
    private Service service;
    
    @Mock
    private Instance instance;
    
    @Mock
    private Subscriber subscriber;
    
    private final String clientId = "127.0.0.1:80#true";
    
    private final String ip = "1.1.1.1";
    
    private final String subscriberIp = "1.1.1.2";
    
    private final int port = 80;
    
    private final int subscriberPort = 8080;
    
    @Before
    public void startUp() {
        boolean ephemeral = true;
        client = new IpPortBasedClient(clientId, ephemeral);
        when(clientManagerDelegate.getClient(clientId)).thenReturn(client);
        when(service.getNamespace()).thenReturn("public");
        when(instance.getIp()).thenReturn(ip);
        when(instance.getPort()).thenReturn(port);
        when(subscriber.getIp()).thenReturn(subscriberIp);
        when(subscriber.getPort()).thenReturn(subscriberPort);
        ephemeralClientOperationService = new EphemeralClientOperationServiceImpl(clientManagerDelegate);
    }
    
    @Test
    public void testRegisterAndDeregisterInstance() throws NoSuchFieldException, IllegalAccessException {
        ephemeralClientOperationService.registerInstance(service, instance, clientId);
        Field fieldClientManager = EphemeralClientOperationServiceImpl.class.getDeclaredField("clientManager");
        fieldClientManager.setAccessible(true);
        ClientManager clientManager = (ClientManager) fieldClientManager.get(ephemeralClientOperationService);
        InstancePublishInfo instancePublishInfo1 = clientManager.getClient(clientId).getInstancePublishInfo(service);
        assertEquals(instancePublishInfo1.getIp(), ip);
        assertEquals(instancePublishInfo1.getPort(), port);
        ephemeralClientOperationService.deregisterInstance(service, instance, clientId);
        InstancePublishInfo instancePublishInfo2 = clientManager.getClient(clientId).getInstancePublishInfo(service);
        assertNull(instancePublishInfo2);
    }
    
    @Test
    public void testSubscribeAndUnsubscribeService() throws NoSuchFieldException, IllegalAccessException {
        ephemeralClientOperationService.subscribeService(service, subscriber, clientId);
        Field fieldClientManager = EphemeralClientOperationServiceImpl.class.getDeclaredField("clientManager");
        fieldClientManager.setAccessible(true);
        ClientManager clientManager = (ClientManager) fieldClientManager.get(ephemeralClientOperationService);
        Subscriber subscriber1 = clientManager.getClient(clientId).getSubscriber(service);
        assertEquals(subscriber1.getIp(), subscriberIp);
        assertEquals(subscriber1.getPort(), subscriberPort);
        ephemeralClientOperationService.unsubscribeService(service, subscriber, clientId);
        Subscriber subscriber2 = client.getSubscriber(service);
        assertNull(subscriber2);
    }
}