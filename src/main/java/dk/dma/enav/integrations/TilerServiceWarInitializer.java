package dk.dma.enav.integrations;

import org.apache.camel.spring.boot.FatJarRouter;
import org.apache.camel.spring.boot.FatWarInitializer;

/**
 * Created by Oliver Steensen-Bech Haagh on 18-07-16.
 */
public class TilerServiceWarInitializer extends FatWarInitializer {
    @Override
    protected Class<? extends FatJarRouter> routerClass() {
        return TilerServiceRouter.class;
    }
}
