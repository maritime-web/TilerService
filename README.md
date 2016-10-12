# TilerService

Integration point responsible for fetching satellite images and sending them to a Docker container running [MapTiler Pro](https://hub.docker.com/r/klokantech/maptiler/).
The tiled images are server by a [tile server](https://hub.docker.com/r/klokantech/tileserver-php/) also running in a Docker container.

## Prerequisites

* Java 8 or later

* Maven

* Docker

* A license for MapTiler Pro

* A file called application.properties

## Configuration

The application.properties must be placed in src/main/java/dk.dma.enav.integrations/resources.
It must at least contain these lines:

    mapTiler.license = <license for MapTiler Pro>
    
    tiles.hostDirectory = <where you want to store satellite images fetched from ftp server>
    
## Execution
Before you can run the program you must do the following:

    docker pull klokantech/maptiler
    docker pull klokantech/tileserver-php
    docker pull dmadk/satellite-consumer:latest
    docker pull dmadk/satellite-consumer:newest

The program should be executed as described at https://hub.docker.com/r/dmadk/tiler-service/ 

