package com.student.agent.mcp;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WeatherMcpClient {

    private static final Logger log = LoggerFactory.getLogger(WeatherMcpClient.class);

    private final RestClient.Builder restClientBuilder;

    @Value("${app.weather.base-url}")
    private String baseUrl;

    @Value("${app.weather.latitude}")
    private String latitude;

    @Value("${app.weather.longitude}")
    private String longitude;

    @Value("${app.weather.campus-name}")
    private String campusName;

    public WeatherMcpClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public String queryWeather(String query) {
        try {
            RestClient client = restClientBuilder.baseUrl(baseUrl).build();
            Map<String, Object> response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/forecast")
                            .queryParam("latitude", latitude)
                            .queryParam("longitude", longitude)
                            .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                            .queryParam("timezone", "Asia/Shanghai")
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                return buildMockWeather(query);
            }
            return campusName + "天气查询成功，原始结果：" + response;
        } catch (Exception ex) {
            log.warn("Weather API failed, fallback to mock response: {}", ex.getMessage());
            return buildMockWeather(query);
        }
    }

    private String buildMockWeather(String query) {
        String day = query.contains("明天") ? "明天" : "今天";
        return day + campusName + "多云转小雨，18-24℃，降水概率60%。建议随身带伞。";
    }
}
