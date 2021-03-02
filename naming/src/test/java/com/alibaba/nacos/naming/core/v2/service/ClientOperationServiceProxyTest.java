/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
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

package com.alibaba.nacos.naming.core.v2.service;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.naming.core.v2.ServiceManager;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.core.v2.service.impl.EphemeralClientOperationServiceImpl;
import com.alibaba.nacos.naming.core.v2.service.impl.PersistentClientOperationServiceImpl;
import com.alibaba.nacos.naming.pojo.Subscriber;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ClientOperationServiceProxyTest {
    
    private String ephemeralClientId = "127.0.0.1:80#true";
    
    private String persistentClientId = "127.0.0.1:8080#false";
    
    private ClientOperationServiceProxy clientOperationServiceProxy;
    
    @Mock
    private EphemeralClientOperationServiceImpl ephemeralClientOperationService;
    
    @Mock
    private PersistentClientOperationServiceImpl persistentClientOperationService;
    
    @Mock
    private Service service;
    
    @Mock
    private Instance ephemeralInstance;
    
    @Mock
    private Instance persistentInstance;
    
    @Mock
    private Subscriber subscriber;
    
    @Before
    public void setUp() throws Exception {
        clientOperationServiceProxy = new ClientOperationServiceProxy(
                ephemeralClientOperationService, persistentClientOperationService);
        when(ephemeralInstance.isEphemeral()).thenReturn(true);
        when(persistentInstance.isEphemeral()).thenReturn(false);
        when(service.getNamespace()).thenReturn("public");
        ServiceManager.getInstance().getSingleton(service);
    }
    
    @Test
    public void testChooseEphemeralClientOperationService() {
        clientOperationServiceProxy.registerInstance(service, ephemeralInstance, ephemeralClientId);
        verify(ephemeralClientOperationService).registerInstance(service, ephemeralInstance, ephemeralClientId);
        verify(persistentClientOperationService, never()).registerInstance(service, ephemeralInstance, ephemeralClientId);
        
        clientOperationServiceProxy.deregisterInstance(service, ephemeralInstance, ephemeralClientId);
        verify(ephemeralClientOperationService).deregisterInstance(service, ephemeralInstance, ephemeralClientId);
        verify(persistentClientOperationService, never()).deregisterInstance(service, ephemeralInstance, ephemeralClientId);
        
        clientOperationServiceProxy.subscribeService(service, subscriber, ephemeralClientId);
        verify(ephemeralClientOperationService).subscribeService(service, subscriber, ephemeralClientId);
        verify(persistentClientOperationService, never()).subscribeService(service, subscriber, ephemeralClientId);
        
        clientOperationServiceProxy.unsubscribeService(service, subscriber, ephemeralClientId);
        verify(ephemeralClientOperationService).unsubscribeService(service, subscriber, ephemeralClientId);
        verify(persistentClientOperationService, never()).unsubscribeService(service, subscriber, ephemeralClientId);
    }
    
    @Test
    public void testChoosePersistentClientOperationService() {
        clientOperationServiceProxy.registerInstance(service, persistentInstance, persistentClientId);
        verify(ephemeralClientOperationService, never()).registerInstance(service, persistentInstance, persistentClientId);
        verify(persistentClientOperationService).registerInstance(service, persistentInstance, persistentClientId);
        
        clientOperationServiceProxy.deregisterInstance(service, persistentInstance, persistentClientId);
        verify(ephemeralClientOperationService, never()).deregisterInstance(service, persistentInstance, persistentClientId);
        verify(persistentClientOperationService).deregisterInstance(service, persistentInstance, persistentClientId);
    }
    
    @After
    public void tearDown() throws Exception {
    }
}