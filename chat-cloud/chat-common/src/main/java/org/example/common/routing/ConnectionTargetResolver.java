package org.example.common.routing;

import java.net.URI;
import java.util.Optional;
import org.example.common.constant.ServiceNames;
import org.springframework.cloud.client.discovery.DiscoveryClient;

/** 从受信任的服务发现列表按实例标识定位；不接受客户端提供的 URL。 */
public class ConnectionTargetResolver {
    public static final String METADATA_KEY = "connection-instance-id";
    private final DiscoveryClient discovery;

    public ConnectionTargetResolver(DiscoveryClient discovery) { this.discovery = discovery; }

    public Optional<URI> resolve(String instanceId) {
        return discovery.getInstances(ServiceNames.CHAT_CONNECTION).stream()
                .filter(i -> instanceId.equals(i.getMetadata().get(METADATA_KEY)))
                .map(i -> i.getUri()).findFirst();
    }
}
