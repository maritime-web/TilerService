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
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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

    @Override
    public void configure() {
        log.info("" + docker.infoCmd().exec());
        this.getContext().setTracing(true);
        this.onException(Exception.class)
                .maximumRedeliveries(6)
                .process(exchange -> {
                    log.error("Exchange failed for: " + exchange.getIn().getHeader(Exchange.FILE_NAME_ONLY));
                });

        from("ftp://{{tiles.user}}@{{tiles.server}}{{tiles.directory}}?password={{tiles.password}}&passiveMode=true" +
                "&localWorkDirectory=/tmp&idempotent=true&consumer.bridgeErrorHandler=true&binary=true&delay=15m")
                .to("file://{{tiles.localDirectory}}?fileExist=Ignore");

        from("file://{{tiles.localDirectory}}?filter=#correctExtension")
                .process(exchange -> {
                    String fileName = (String) exchange.getIn().getHeader(Exchange.FILE_NAME);
                    CreateContainerResponse container = docker.createContainerCmd("klokantech/maptiler")
                            .withEnv(String.format("MAPTILER_LICENSE=%s", mapTilerLicense))
                            .withCmd("maptiler", "-o",
                                    fileName.replace(".jpg", ""), "-nodata", "0", "0", "0", "-zoom", "3", "12",
                                    "-P", "4", fileName)
                            .withBinds(new Bind(localDir, new Volume("/data")))
                            .exec();
                    docker.startContainerCmd(container.getId()).exec();
                    int exitCode = docker.waitContainerCmd(container.getId()).exec(new WaitContainerResultCallback())
                            .awaitStatusCode();
                    if (exitCode == 0) {
                        log.info("Tiling completed");
                    } else {
                        log.error("Tiling failed");
                    }
                    docker.removeContainerCmd(container.getId()).exec();
                });
    }

    @Bean
    GenericFileFilter correctExtension() {
        return file -> {
            String fileName = file.getFileNameOnly();
            return fileName.endsWith(".tif") || fileName.endsWith(".jpg");
        };
    }
}
