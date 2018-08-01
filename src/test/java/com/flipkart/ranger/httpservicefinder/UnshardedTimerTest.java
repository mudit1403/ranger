/**
 * Copyright 2015 Flipkart Internet Pvt. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flipkart.ranger.httpservicefinder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.ranger.ServiceFinderBuilders;
import com.flipkart.ranger.finder.HttpSourceConfig;
import com.flipkart.ranger.finder.unsharded.UnshardedClusterFinder;
import com.flipkart.ranger.finder.unsharded.UnshardedClusterInfo;
import com.flipkart.ranger.healthcheck.HealthcheckStatus;
import com.flipkart.ranger.model.ListDeserializer;
import com.flipkart.ranger.model.ServiceNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

public class UnshardedTimerTest {
    private static final Logger logger = LoggerFactory.getLogger(UnshardedTimerTest.class);
    private ObjectMapper objectMapper;
    private WireMockServer wireMockServer;

    @Before
    public void startTestCluster() throws Exception{
        objectMapper = new ObjectMapper();
        wireMockServer = new WireMockServer();
        wireMockServer.start();
        configureFor("localhost", 8080);
        Instant instant = Instant.now();
        long timeStampMillis = instant.toEpochMilli();
        String json = "[{\"host\":\"localhost\",\"port\":31649,\"nodeData\":{\"environment\":\"stage\"},\"healthcheckStatus\":\"healthy\",\"lastUpdatedTimeStamp\":"+ Long.toString(timeStampMillis) + "}, {\"host\":\"localhost\",\"port\":31648,\"nodeData\":{\"environment\":\"stage\"},\"healthcheckStatus\":\"healthy\",\"lastUpdatedTimeStamp\":"+ Long.toString(timeStampMillis) + "}, {\"host\":\"localhost\",\"port\":31647,\"nodeData\":{\"environment\":\"stage\"},\"healthcheckStatus\":\"unhealthy\",\"lastUpdatedTimeStamp\":"+ Long.toString(timeStampMillis) + "}]";
        logger.debug(json);
        stubFor(get(urlEqualTo("/test")).willReturn(aResponse().withBody(json)));
    }

    @After
    public void stopTestCluster() throws Exception {
        wireMockServer.stop();
    }

    @Test
    public void testBasicDiscovery() throws Exception {
        ListDeserializer<UnshardedClusterInfo> listDeserializer = new ListDeserializer<UnshardedClusterInfo>() {
            private AtomicInteger integer = new AtomicInteger(0);

            @Override
            public List<ServiceNode<UnshardedClusterInfo>> deserialize(byte[] data) {
                try {
                    List<ServiceNode<UnshardedClusterInfo>> serviceNodes=
                            objectMapper.readValue(data,
                                    new TypeReference<List<ServiceNode<EnvNodeData>>>() {
                                    });
                    //marking all the ServiceNodes unhealthy after few calls to deserialize
                    if (integer.incrementAndGet() > 2) {
                        for (ServiceNode<UnshardedClusterInfo> serviceNode : serviceNodes) {
                            serviceNode.setHealthcheckStatus(HealthcheckStatus.unhealthy);
                        }
                    }
                    return serviceNodes;
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
        };

        HttpSourceConfig<UnshardedClusterInfo> httpSourceConfig = new HttpSourceConfig<UnshardedClusterInfo>("localhost", 8080, "/test", listDeserializer);

        UnshardedClusterFinder serviceFinder = ServiceFinderBuilders.unshardedFinderBuilder()
                .withHttpSourceConfig(httpSourceConfig)
                .build();

        serviceFinder.start();

        List<ServiceNode<UnshardedClusterInfo>> nodesList = serviceFinder.getAll(null);
        Assert.assertEquals(2, nodesList.size());

        Thread.sleep(3000);

        //NodesList must be empty as all the ServiceNodes are marked unhealthy
        List<ServiceNode<UnshardedClusterInfo>> secondNodesList = serviceFinder.getAll(null);
        Assert.assertEquals(0, secondNodesList.size());

    }

    static class EnvNodeData extends UnshardedClusterInfo{
        private String environment;

        public EnvNodeData() {
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }
    }
}
