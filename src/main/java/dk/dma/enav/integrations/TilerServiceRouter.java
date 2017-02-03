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
import com.spotify.docker.client.messages.PortBinding;
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
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by Oliver Steensen-Bech Haagh on 18-07-16.
 */
@SpringBootApplication
public class TilerServiceRouter extends FatJarRouter {

    private final DockerClient docker = DefaultDockerClient.fromEnv().build();

    @PropertyInject("tiles.localDirectory")
    private String localDir;

    @PropertyInject("tiles.hostDirectory")
    private String hostDir;

    @PropertyInject("mapTiler.license")
    private String mapTilerLicense;

    @PropertyInject("mapTiler.arguments")
    private String mapTilerArguments;

    @PropertyInject("tiles.server.port")
    private String tileServerPort;

    @PropertyInject("tiles.daysToKeep")
    private int daysToKeep;

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

        // create a container for tile server
        Map<String, List<PortBinding>> portBindings = new HashMap<>();
        List<PortBinding> hostPorts = new ArrayList<>();
        hostPorts.add(PortBinding.of("", tileServerPort));
        portBindings.put("80", hostPorts);
        ContainerConfig tileServerConfig = ContainerConfig.builder()
                .hostConfig(HostConfig.builder().appendBinds(String.format("%s:/var/www", hostDir + "/tiles"))
                        .portBindings(portBindings).build())
                .image("klokantech/tileserver-php").exposedPorts("80")
                .build();
        ContainerCreation tileServer = docker.createContainer(tileServerConfig);
        log.info("Starting tile server");
        String tileServerID = tileServer.id();
        docker.startContainer(tileServerID);

        // handle shutdown of tile server container on graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log.info("Stopping tile server");
                try {
                    docker.stopContainer(tileServerID, 300);
                    int exitCode = docker.waitContainer(tileServerID).statusCode();
                    if (exitCode != 0) {
                        log.error("Something went wrong when shutting down tile server");
                        log.error(docker.logs(tileServerID, DockerClient.LogsParam.stderr(),
                                DockerClient.LogsParam.stdout()).readFully());
                    } else {
                        docker.removeContainer(tileServerID);
                    }
                } catch (DockerException e) {
                    log.error(e.toString());
                } catch (InterruptedException e) {
                    log.error(e.toString());
                }
            }
        });

        from("timer:consumer?fixedRate=true&period=12h")
                .process(exchange -> {
                    ContainerConfig consumerConfig = ContainerConfig.builder()
                            .hostConfig(HostConfig.builder().appendBinds(hostDir + ":/data").build())
                            .image("dmadk/satellite-consumer:latest").build();

                    ContainerCreation consumer = docker.createContainer(consumerConfig);
                    String consumerID = consumer.id();

                    docker.startContainer(consumerID);
                    int exitCode = docker.waitContainer(consumerID).statusCode();

                    if (exitCode != 0) {
                        log.error("Consuming of satellite images failed");
                        log.error(docker.logs(consumerID).readFully());
                    } else {
                        docker.removeContainer(consumerID);
                    }
                })
                // for each consumed image check if it has already been tiled
                .process(exchange -> {
                    File imageDir = new File(localDir);
                    File[] images = imageDir.listFiles();
                    File donePath = new File(localDir + "/.done");
                    for (File image : images) {
                        File tiledImage = new File(localDir + "/tiles/" + image.getName());
                        if (tiledImage.exists()) {
                            Files.move(image.toPath(), donePath.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                });

        from("timer:latestConsumer?fixedRate=true&period=3h")
                // Consume the latest satellite images
                .process(exchange -> {
                    ContainerConfig consumerConfig = ContainerConfig.builder()
                            .hostConfig(HostConfig.builder().appendBinds(hostDir + ":/data").build())
                            .image("dmadk/satellite-consumer:newest").build();

                    ContainerCreation consumer = docker.createContainer(consumerConfig);
                    String consumerID = consumer.id();

                    docker.startContainer(consumerID);
                    int exitCode = docker.waitContainer(consumerID).statusCode();

                    if (exitCode != 0) {
                        log.error("Consuming of latest satellite images failed");
                        log.error(docker.logs(consumerID).readFully());
                    } else {
                        docker.removeContainer(consumerID);
                    }
                });

        // send local satellite images to a MapTiler running in a Docker container
        from("file://{{tiles.localDirectory}}?filter=#correctExtension&consumer.bridgeErrorHandler=true" +
                "&delay=5m&initialDelay=10000&move=.done&readLock=changed&readLockMinAge=5m&readLockTimeout=303s")
                .process(exchange -> {
                    String fileName = (String) exchange.getIn().getHeader(Exchange.FILE_NAME);
                    String fileNameWithoutExtension = fileName.replace(".jpg", "").replace(".tif", "");

                    File tileSet = new File(localDir + "/tiles/" + fileNameWithoutExtension);

                    // if a tileset for the current image already exists then delete the tileset
                    if (tileSet.exists()) {
                        boolean deleted = FileUtils.deleteQuietly(tileSet);
                        if (!deleted) {
                            log.error(tileSet.getName() + " could not be deleted");
                        }
                    }

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
                                    .appendBinds(String.format("%s:/data", hostDir)).build())
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
                    } else {
                        log.error("Tiling failed for " + fileName + " in container " + containerID);
                        log.error(docker.logs(containerID, DockerClient.LogsParam.stderr(),
                                DockerClient.LogsParam.stdout()).readFully());
                    }
                })
                // send auxiliary files to the .done directory when map tiling has finished
                .process(exchange -> {
                    // assumes that satellite images either ends with .jpg or .tif
                    // may have to be changed in the future
                    String fileNameWithoutExtension = ((String) exchange.getIn().getHeader(Exchange.FILE_NAME))
                            .replace(".jpg", "").replace(".tif", "");
                    File path = new File(localDir);
                    File newPath = new File(path, ".done");

                    File[] files = path.listFiles(file -> file.getName().contains(fileNameWithoutExtension)
                            && !file.getName().contains(".camelLock")
                            && !file.equals(new File((String) exchange.getIn().getHeader(Exchange.FILE_PATH))));

                    for (File file : files) {
                        File newFile = new File(newPath, file.getName());
                        Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    }
                });

        // delete old tiles and images
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

    // deletes tiles and images that are older than the specified threshold
    private void deleteOldTilesAndImages() throws IOException {
        if (daysToKeep < 0) {
            return;
        } else {
            File tilesPath = new File(localDir + "/tiles");
            File donePath = new File(localDir + "/.done");

            // get all directories that are older than daysToKeep
            FileFilter tilesFilter = file -> {
                // do not delete files for Tile Server
                if (file.getName().equals("README.md") || file.getName().equals("tileserver.php")
                        || file.getName().equals(".htaccess") || file.getName().equals(".travis.yml")
                        || file.getName().equals("robots.txt")) {
                    return false;
                }

                long fileLastModified = file.lastModified();

                long diff = Calendar.getInstance().getTimeInMillis() - fileLastModified;
                // converts the difference to days
                long days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);

                // we want to keep stored tiles for 2 extra days before they are deleted
                // in case somebody might need them
                return days > (daysToKeep + 2);
            };

            FileFilter imageFilter = file -> {
                long fileLastModified = file.lastModified();

                long diff = Calendar.getInstance().getTimeInMillis() - fileLastModified;
                // converts the difference to days
                long days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);

                return days > daysToKeep;
            };

            File[] tileSubPaths = tilesPath.listFiles(tilesFilter);
            File[] doneFiles = donePath.listFiles(imageFilter);
            File[] allToDelete = (File[]) ArrayUtils.addAll(tileSubPaths, doneFiles);

            for (File file : allToDelete) {
                boolean deleted = FileUtils.deleteQuietly(file);
                if (!deleted) {
                    log.error("Old tiles and images deleter: " + file.getName() + " could not be deleted");
                } else {
                    log.info("Old tiles and images deleter: " + file.getName() + " was deleted");
                }
            }
        }
    }
}