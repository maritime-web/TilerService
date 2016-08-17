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


import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.HostConfig;
import org.apache.camel.Exchange;
import org.apache.camel.PropertyInject;
import org.apache.camel.component.file.GenericFileFilter;
import org.apache.camel.spring.boot.FatJarRouter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ArrayUtils;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Created by Oliver Steensen-Bech Haagh on 18-07-16.
 */
@SpringBootApplication
public class TilerServiceRouter extends FatJarRouter {

    private final DockerClient docker = DefaultDockerClient.fromEnv().build();

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

    @PropertyInject("tracing")
    private boolean tracing;

    public TilerServiceRouter() throws DockerCertificateException {
    }

    @Override
    public void configure() throws DockerException, InterruptedException {
        log.info("" + docker.info());
        // set true for detailed tracing of routes
        this.getContext().setTracing(tracing);
        this.onException(Exception.class)
                .maximumRedeliveries(6)
                .process(exchange -> log.error("Exchange failed for: " +
                        exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY)));
        // split the specified arguments for the MapTiler container
        String[] mapTilerArgs = mapTilerArguments.split(" ");

        // fetch satellite images from provider and save them in a local directory
        from("ftp://{{dmi.user}}@{{dmi.server}}/{{dmi.directory}}?password={{dmi.password}}&passiveMode=true" +
                "&localWorkDirectory=/tmp&idempotent=true&consumer.bridgeErrorHandler=true&binary=true&delay=15m" +
                "&filter=#notTooOld")
                .to("file://{{tiles.localDirectory}}?fileExist=Ignore");

        from("ftp://{{dmi.user}}@{{dmi.server}}/{{dmi.directory}}?password={{dmi.password}}&consumer.bridgeErrorHandler=true" +
                "&download=false&delete=true&filter=#tooOldOnFTP")
                .process(exchange -> log.info(exchange.getIn().getHeader(Exchange.FILE_NAME) + " was deleted on FTP server"));

        // send local satellite images to a MapTiler running in a Docker container
        from("file://{{tiles.localDirectory}}?filter=#correctExtension&consumer.bridgeErrorHandler=true" +
                "&delay=15m&initialDelay=10000&move=.done")
                .process(exchange -> {
                    String fileName = (String) exchange.getIn().getHeader(Exchange.FILE_NAME);
                    String fileNameWithoutExtension = fileName.replace(".jpg", "").replace(".tif", "");

                    // build a list of arguments for the MapTiler
                    ArrayList<String> arguments = new ArrayList<>();
                    arguments.add("maptiler");
                    arguments.add("-o");
                    arguments.add("tiles/" + fileNameWithoutExtension);
                    Collections.addAll(arguments, mapTilerArgs);
                    arguments.add(fileName);

                    // create container for MapTiler
                    ContainerConfig config = ContainerConfig.builder()
                            .hostConfig(HostConfig.builder()
                                    .appendBinds(String.format("%s:/data", localDir)).build())
                            .image("klokantech/maptiler")
                            .env(String.format("MAPTILER_LICENSE=%s", mapTilerLicense))
                            .cmd(arguments)
                            .build();
                    ContainerCreation container = docker.createContainer(config);

                    log.info("Starting tiling of file " + fileName);
                    String containerID = container.id();
                    //log.info("" + docker.inspectContainerCmd(containerID).exec());
                    docker.startContainer(containerID);

                    // get the exit code when the container finishes and check if the tiling job was successful or not
                    int exitCode = docker.waitContainer(containerID).statusCode();

                    if (exitCode == 0) {
                        log.info("Tiling completed for " + fileName);
                        docker.removeContainer(containerID);

                        // Set permissions for the newly generated directory
                        ContainerConfig fixerConfig = ContainerConfig.builder()
                                .hostConfig(HostConfig.builder()
                                    .appendBinds(String.format("%s/tiles/%s:/data", localDir, fileNameWithoutExtension))
                                        .build())
                                .image("dmadk/permissions-fixer").build();
                        ContainerCreation fixer = docker.createContainer(fixerConfig);
                        String fixerID = fixer.id();
                        docker.startContainer(fixerID);

                        int fixerExitCode = docker.waitContainer(fixerID).statusCode();

                        if (fixerExitCode != 0) {
                            log.error("Setting permissions for " + fileNameWithoutExtension + " failed");
                        } else {
                            docker.removeContainer(fixerID);
                        }
                    } else {
                        log.error("Tiling failed for " + fileName + " in container " + containerID);
                    }
                })
                // send auxiliary files to the .done directory when map tiling has finished
                .process(exchange -> {
                    // assumes that satellite images either ends with .jpg or .tif
                    // may have to changed in the future
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

        // remove old tiles from the tiles directory
        from("timer:deleteOldFilesTimer?period=6h&fixedRate=true")
                .process(exchange -> deleteOldTilesAndImages());
    }

    // A filter for the file consumer to only consume .jpg or .tif files
    @Bean
    GenericFileFilter correctExtension() {
        return file -> {
            String fileName = file.getFileNameOnly();
            return (fileName.endsWith(".tif") || fileName.endsWith(".jpg"));
        };
    }

    // filter for not consuming old files from ftp server
    @Bean
    GenericFileFilter notTooOld() {
        return file -> {
            if (daysToKeep < 0) return true;
            else {
                long fileLastModified = file.getLastModified();

                // the difference in milliseconds for the time now
                // and the time that the file was last modified
                long diff = Calendar.getInstance().getTimeInMillis() - fileLastModified;
                // converts the difference to days
                long days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);

                return days <= daysToKeep;
            }
        };
    }

    // filter for files that are too old on FTP server
    @Bean
    GenericFileFilter tooOldOnFTP() {
        return file -> {
            if (daysToKeepOnServer < 0) return false;
            else {
                long fileLastModified = file.getLastModified();

                // the difference in milliseconds for the time now
                // and the time that the file was last modified
                long diff = Calendar.getInstance().getTimeInMillis() - fileLastModified;
                // converts the difference to days
                long days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);

                return days > daysToKeepOnServer;
            }
        };
    }

    // deletes tiles and images that are older than the specified threshold
    private void deleteOldTilesAndImages() throws IOException {
        if (daysToKeep < 0) {
            return;
        } else {
            File tilesPath = new File(localDir + "/tiles");
            File donePath = new File(localDir + "/.done");

            // get all directories that are older than daysToKeep
            FileFilter filter = file -> {
                long fileLastModified = file.lastModified();

                long diff = Calendar.getInstance().getTimeInMillis() - fileLastModified;
                // converts the difference to days
                long days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);

                return days > daysToKeep;
            };

            File[] tileSubPaths = tilesPath.listFiles(filter);
            File[] doneFiles = donePath.listFiles(filter);
            File[] allToDelete = (File[]) ArrayUtils.addAll(tileSubPaths, doneFiles);

            for (File file : allToDelete) {
                FileUtils.deleteDirectory(file);
                log.info("Old tiles and images deleter: " + file.getName() + " was deleted");
            }
        }
    }
}