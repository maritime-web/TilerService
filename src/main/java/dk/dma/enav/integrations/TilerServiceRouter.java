package dk.dma.enav.integrations;

import org.apache.camel.Exchange;
import org.apache.camel.component.file.GenericFileFilter;
import org.apache.camel.spring.boot.FatJarRouter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Created by Oliver Steensen-Bech Haagh on 18-07-16.
 */
@SpringBootApplication
public class TilerServiceRouter extends FatJarRouter {

    @Override
    public void configure() {
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
