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

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import org.apache.camel.Exchange;
import org.apache.camel.PropertyInject;
import org.apache.camel.component.file.GenericFileFilter;
import org.apache.camel.spring.boot.FatJarRouter;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * Created by Oliver Steensen-Bech Haagh on 18-07-16.
 */
@SpringBootApplication
public class TilerServiceRouter extends FatJarRouter {

    private DockerClient docker = DockerClientBuilder.getInstance().build();

    @PropertyInject("tiles.localDirectory")
    private String localDir;

    @PropertyInject("mapTiler.license")
    private String mapTilerLicense;

    @PropertyInject("mapTiler.arguments")
    private String mapTilerArguments;

    @PropertyInject("tiles.daysToKeep")
    private int daysToKeep;

    @PropertyInject("dmi.daysToKeep")
    private int daysToKeepOnServer;

    @PropertyInject("user.id")
    private String userID;

    @PropertyInject("tracing")
    private boolean tracing;

    @Bean
    FTPClient ftp() {
        return new FTPClient();
    }

    @Override
    public void configure() {
        log.info("" + docker.infoCmd().exec());
        // set true for detailed tracing of routes
        this.getContext().setTracing(tracing);
        this.onException(Exception.class)
                .maximumRedeliveries(6)
                .process(exchange -> {
                    log.error("Exchange failed for: " + exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY));
                });
        String[] mapTilerArgs = mapTilerArguments.split(" ");

        // fetch satellite images from provider and save them in a local directory
        from("ftp://{{dmi.user}}@{{dmi.server}}{{dmi.directory}}?password={{dmi.password}}&passiveMode=true" +
                "&localWorkDirectory=/tmp&idempotent=true&consumer.bridgeErrorHandler=true&binary=true&delay=15m" +
                "ftpClient=#ftp")
                .to("file://{{tiles.localDirectory}}?fileExist=Ignore");

        // send local satellite images to a MapTiler running in a Docker container
        from("file://{{tiles.localDirectory}}?filter=#correctExtension&consumer.bridgeErrorHandler=true" +
                "&delay=15m&initialDelay=10000&move=.done")
                .process(exchange -> {
                    String fileName = (String) exchange.getIn().getHeader(Exchange.FILE_NAME);
                    // build a list of arguments for the MapTiler
                    ArrayList<String> arguments = new ArrayList<>();
                    arguments.add("maptiler");
                    arguments.add("-o");
                    arguments.add("tiles/" + fileName.replace(".jpg", "").replace(".tif", ""));
                    for (String arg: mapTilerArgs) {
                        arguments.add(arg);
                    }
                    arguments.add(fileName);
                    CreateContainerResponse container = docker.createContainerCmd("klokantech/maptiler")
                            .withEnv(String.format("MAPTILER_LICENSE=%s", mapTilerLicense))
                            .withCmd(arguments)
                            .withBinds(new Bind(localDir, new Volume("/data")))
                            .withUser(userID)
                            .exec();
                    log.info("Starting tiling of file " + fileName);
                    String containerID = container.getId();
                    //log.info("" + docker.inspectContainerCmd(containerID).exec());
                    docker.startContainerCmd(containerID).exec();
                    // get the exit code when the container finishes and check if the tiling job was successful or not
                    int exitCode = docker.waitContainerCmd(containerID).exec(new WaitContainerResultCallback())
                            .awaitStatusCode();
                    if (exitCode == 0) {
                        log.info("Tiling completed for " + fileName);
                        docker.removeContainerCmd(containerID).exec();
                    } else {
                        log.error("Tiling failed for " + fileName + " in container " + containerID);
                    }
                })
                // send auxiliary files to the .done directory
                .process(exchange -> {
                    String fileNameWithoutExtension = ((String) exchange.getIn().getHeader(Exchange.FILE_NAME))
                            .replace(".jpg", "").replace(".tif", "");
                    File path = new File(localDir);
                    File newPath = new File(path, ".done");

                    File[] files = path.listFiles(file -> file.getName().contains(fileNameWithoutExtension) &&
                            !file.equals(new File((String) exchange.getIn().getHeader(Exchange.FILE_PATH))));

                    for (File file : files) {
                        File newFile = new File(newPath, file.getName());
                        Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    }
                });

        /*// delete files that are older than the specified number of days
        from("file://{{tiles.localDirectory}}?filter=#tooOld&recursive=true&delete=true&delay=12h" +
                "&consumer.bridgeErrorHandler=true")
                .process(exchange -> {
                    //log.info(exchange.getIn().getHeader(Exchange.FILE_NAME) + " deleted");
                });*/

        //from("ftp://{{dmi.user}}@{{dmi.server}}{{dmi.directory}}?password={{dmi.password}}&filter=#tooOldOnServer" +
        //        "&delete=true&consumer.bridgeErrorHandler=true");
    }

    // A filter for the file consumer to only consume .jpg or .tif files
    @Bean
    GenericFileFilter correctExtension() {
        return file -> {
            String fileName = file.getFileNameOnly();
            return (fileName.endsWith(".tif") || fileName.endsWith(".jpg"));
        };
    }

    @Bean
    GenericFileFilter tooOld() {
        return file -> {
            if (daysToKeep == -1) return false;
            else {
                long fileLastModified = file.getLastModified();

                // the difference in milliseconds for the time now
                // and the time that the file was last modified
                long diff = Calendar.getInstance().getTimeInMillis() - fileLastModified;
                // converts the difference to days
                long days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);

                if (days > daysToKeep) {
                    return true;
                }
            }
            return false;
        };
    }
}
