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

import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Date;
import java.util.GregorianCalendar;

/**
 * Created by Oliver Steensen-Bech Haagh on 8/16/16.
 *
 * A base class for testing
 */
@Ignore
@DirtiesContext
public abstract class TilerServiceTestBase extends CamelTestSupport {

    protected FakeFtpServer fakeFtpServer;

    protected int serverPort;

    @Autowired
    protected ModelCamelContext context;

    @Before
    public void setUp() throws Exception {
        fakeFtpServer = new FakeFtpServer();
        fakeFtpServer.setServerControlPort(0);
        fakeFtpServer.addUserAccount(new UserAccount("user", "password", "/target/test-classes/files/"));
        FileSystem fileSystem = new UnixFakeFileSystem();
        FileEntry image1 = new FileEntry("/target/test-classes/files/image1.jpg");
        image1.setLastModified(new Date());
        fileSystem.add(image1);
        FileEntry image2 = new FileEntry("/target/test-classes/files/image2.jpg");
        image2.setLastModified(new GregorianCalendar(2011, 9, 30).getTime());
        fileSystem.add(image2);
        fakeFtpServer.setFileSystem(fileSystem);
        fakeFtpServer.start();
        serverPort = fakeFtpServer.getServerControlPort();
    }

    @DirtiesContext
    @Test
    public abstract void test() throws Exception;

    @After
    public void tearDown() throws Exception {
        fakeFtpServer.stop();
        fakeFtpServer = null;

        context.stop();
        context = null;
    }

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }
}
