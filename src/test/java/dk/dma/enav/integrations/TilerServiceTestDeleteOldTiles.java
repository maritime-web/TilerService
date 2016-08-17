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

import org.apache.camel.builder.NotifyBuilder;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.GregorianCalendar;

/**
 * Created by Oliver Steensen-Bech Haagh on 8/17/16.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ActiveProfiles("unittest")
@SpringBootTest(classes = TilerServiceRouter.class)
@DirtiesContext
public class TilerServiceTestDeleteOldTiles extends CamelTestSupport {

    @Autowired
    private ModelCamelContext context;

    private String localDir;

    @Before
    public void setUp() throws IOException {
        localDir = "${tiles.localDirectory}";
        context.getRouteDefinitions().forEach(routeDefinition -> routeDefinition.stop());

        File notTooOld = new File(localDir + "/file1");
        notTooOld.setLastModified(new Date().getTime());

        File tooOld = new File(localDir + "/file2");
        tooOld.setLastModified(new GregorianCalendar(2011, 9, 30).getTime().getTime());
        context.getRouteDefinitions().forEach(routeDefinition -> routeDefinition.stop());
    }

    // test that old local files gets deleted as they should
    @Test
    public void test() throws Exception {
        NotifyBuilder notify = new NotifyBuilder(context).fromRoute(context.getRouteDefinitions().get(3).getId())
                .whenDone(1).create();

        boolean done = notify.matches();

        if (done) {
            File path = new File(localDir);
            assertEquals(1, path.listFiles().length);
            assertEquals("file1", path.listFiles()[0].getName());
        }

        context.stop();
    }
}
