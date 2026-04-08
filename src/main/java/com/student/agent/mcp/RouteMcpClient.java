package com.student.agent.mcp;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class RouteMcpClient {

    private static final Logger log = LoggerFactory.getLogger(RouteMcpClient.class);

    private final RestClient.Builder restClientBuilder;

    @Value("${app.amap.base-url}")
    private String baseUrl;

    @Value("${app.amap.api-key:}")
    private String apiKey;

    public RouteMcpClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public String queryRoute(String query) {
        String[] endpoints = extractRoute(query);
        if (!StringUtils.hasText(apiKey)) {
            return "路线规划（模拟结果）：从" + endpoints[0] + "到" + endpoints[1] + "，建议步行12分钟，如需真实导航请配置高德地图 API Key。";
        }
        try {
            RestClient client = restClientBuilder.baseUrl(baseUrl).build();
            Map<String, Object> response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/v5/direction/walking")
                            .queryParam("key", apiKey)
                            .queryParam("origin", "121.4737,31.2304")
                            .queryParam("destination", "121.4800,31.2350")
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return "路线规划结果：" + response;
        } catch (Exception ex) {
            log.warn("Route API failed, fallback to mock response: {}", ex.getMessage());
            return "路线规划（降级结果）：从" + endpoints[0] + "到" + endpoints[1] + "，建议步行12分钟。";
        }
    }

    private String[] extractRoute(String query) {
        if (query.contains("到")) {
            String[] split = query.replace("怎么走", "").replace("路线", "").split("到");
            if (split.length >= 2) {
                return new String[]{split[0].replace("从", "").trim(), split[1].trim()};
            }
        }
        return new String[]{"图书馆", "实验楼"};
    }
}
