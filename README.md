# TilerService

Integration point responsible for fetching satellite images and sending them to a Docker container running MapTiler Pro.

## Prerequisites

* Java 8 or later

* Maven

* Docker

* A license for MapTiler Pro

* A file called application.properties

## Configuration

The application.properties must be placed in src/main/java/dk.dma.enav.integrations/resources.
It must at least contain these lines:

    dmi.server = <ftp server address>
    dmi.user = <ftp user>
    dmi.password = <ftp password>
    dmi.directory = <ftp directory>
    
    mapTiler.license = <license for MapTiler Pro>
    
    tiles.localDirectory = <where you want to store satellite images fetched from ftp server>
    
## Execution

To run the program you can use Maven and Spring Boot:

    mvn spring-boot:run
    
A Docker based setup is also on the way.