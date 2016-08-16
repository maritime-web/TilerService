/* Copyright (c) 2011 Danish Maritime Authority.
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
 */
package dk.dma.enav.integrations;

import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.ToDefinition;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * Created by Oliver Steensen-Bech Haagh on 8/10/16.
 */

@RunWith(SpringJUnit4ClassRunner.class)
@ActiveProfiles("unittest")
@SpringBootTest
public class TilerServiceTest {

    private FakeFtpServer fakeFtpServer;

    private int serverPort;

    @Autowired
    private ModelCamelContext context;

    @Before
    public void setUp() {
        fakeFtpServer = new FakeFtpServer();
        fakeFtpServer.setServerControlPort(0);
        fakeFtpServer.addUserAccount(new UserAccount("user", "password", "/target/test-classes"));
        FileSystem fileSystem = new UnixFakeFileSystem();
        FileEntry image1 = new FileEntry("/target/test-classes/image1.jpg");
        image1.setLastModified(new Date());
        fileSystem.add(image1);
        FileEntry image2 = new FileEntry("/target/test-classes/image2.jpg");
        image2.setLastModified(new GregorianCalendar(2011, 9, 30).getTime());
        fileSystem.add(image2);
        fakeFtpServer.setFileSystem(fileSystem);
        fakeFtpServer.start();
        serverPort = fakeFtpServer.getServerControlPort();
    }

    @Test
    public void testFtpFilter() throws Exception {
        context.getRouteDefinitions().forEach(routeDefinition -> routeDefinition.stop());
        context.getRouteDefinitions().get(0).adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                replaceFromWith("ftp://user@127.0.0.1:" + serverPort + "?password=password&filter=#notTooOld");
                weaveByType(ToDefinition.class).selectFirst().replace().to("mock:end");
            }
        });
        MockEndpoint end = context.getEndpoint("mock:end", MockEndpoint.class);
        end.expectedMessageCount(1);

        end.assertIsSatisfied();

        String fileName = (String) end.getReceivedExchanges().get(0).getIn().getHeader(Exchange.FILE_NAME);
        assertEquals("image1.jpg", fileName);
    }

    @Test
    public void testTooOldFtp() throws Exception {
        context.getRouteDefinitions().forEach(routeDefinition -> routeDefinition.stop());
        RouteDefinition route = context.getRouteDefinitions().get(1);
        route.adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                replaceFromWith("ftp://user@127.0.0.1:" + serverPort + "?password=password&filter=#tooOldOnFTP&delete=true");
                weaveByType(ProcessorDefinition.class).replace().to("mock:end");
            }
        });
        MockEndpoint end = context.getEndpoint("mock:end", MockEndpoint.class);
        end.expectedMessageCount(1);

        end.assertIsSatisfied();

        String fileName = (String) end.getReceivedExchanges().get(0).getIn().getHeader(Exchange.FILE_NAME);
        assertEquals("image2.jpg", fileName);

        FTPClient ftpClient = new FTPClient();
        ftpClient.connect("localhost", serverPort);
        ftpClient.login("user", "password");

        ArrayList<String> names = new ArrayList<>(Arrays.asList(ftpClient.listNames()));
        names.forEach(name -> System.out.println(name));

        assertEquals(1, names.size());
        assertEquals("image1.jpg", names.get(0));
        ftpClient.disconnect();
    }

    @After
    public void tearDown() {
        fakeFtpServer.stop();
    }
}
