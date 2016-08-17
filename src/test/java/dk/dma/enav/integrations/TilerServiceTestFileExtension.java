/*
 * Copyright (c) 2011 Danish Maritime Authority.
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
 *
 */

package dk.dma.enav.integrations;

import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Created by Oliver Steensen-Bech Haagh on 8/17/16.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ActiveProfiles("unittest")
@SpringBootTest(classes = TilerServiceRouter.class)
public class TilerServiceTestFileExtension extends CamelTestSupport {

    @Autowired
    private ModelCamelContext context;

    @Test
    public void test() throws Exception {
        context.getRouteDefinitions().forEach(routeDefinition -> routeDefinition.stop());
        RouteDefinition route = context.getRouteDefinitions().get(2);
        route.adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                replaceFromWith("file://{{tiles.localDirectory}}?filter=#correctExtension&noop=true");
                weaveByType(ProcessDefinition.class).replace().to("test:file://target/test-classes/tilesResult?noop=true");
            }
        });
        MockEndpoint.assertIsSatisfied(context);
    }

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }
}
