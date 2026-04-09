package com.student.agent.mcp;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class WeatherMcpClient {

    private static final Logger log = LoggerFactory.getLogger(WeatherMcpClient.class);

    private final RestClient.Builder restClientBuilder;

    // 天气 API (通常是 Open-Meteo)
    @Value("${app.weather.base-url:https://api.open-meteo.com}")
    private String weatherBaseUrl;

    // 高德 API (用于将地名转换为经纬度)
    @Value("${app.amap.base-url:https://restapi.amap.com}")
    private String amapBaseUrl;

    @Value("${app.amap.api-key:}")
    private String amapApiKey;

    // 默认回退位置（当用户没有说地点，或者高德查不到时使用默认校园位置）
    @Value("${app.weather.default-latitude:34.12}")
    private String defaultLatitude;

    @Value("${app.weather.default-longitude:108.83}")
    private String defaultLongitude;

    @Value("${app.weather.campus-name:西安电子科技大学南校区}")
    private String defaultCampusName;

    public WeatherMcpClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    public String queryWeather(String query) {
        try {
            RestClient client = restClientBuilder.build();

            // 1. 解析用户输入中的【时间】和【地点】
            int dayOffset = parseTimeOffset(query);
            String dayStr = (dayOffset == 0) ? "今天" : (dayOffset == 1) ? "明天" : "后天";
            String locationName = parseLocation(query);

            String lat = defaultLatitude;
            String lon = defaultLongitude;
            String realLocationName = defaultCampusName;

            // 2. 如果用户指定了地点，调用高德API获取经纬度
            if (StringUtils.hasText(locationName) && !locationName.equals(defaultCampusName)) {
                if (StringUtils.hasText(amapApiKey)) {
                    String[] locationInfo = getCoordinatesByAmap(client, locationName);
                    if (locationInfo != null) {
                        String[] coords = locationInfo[0].split(",");
                        lon = coords[0];
                        lat = coords[1];
                        realLocationName = locationInfo[1]; // 使用高德官方名称
                    } else {
                        return "未找到【" + locationName + "】的位置信息，请提供更明确的城市或地名。";
                    }
                } else {
                    return "检测到您想查询【" + locationName + "】的天气，但系统未配置高德API Key，仅能查询默认校区天气。";
                }
            }

            // 3. 调用天气 API (Open-Meteo) 获取天气预报
            Map<String, Object> response = client.get()
                    .uri(weatherBaseUrl + "/v1/forecast?latitude={lat}&longitude={lon}&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=Asia/Shanghai", lat, lon)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response == null || !response.containsKey("daily")) {
                return buildMockWeather(realLocationName, dayStr);
            }

            // 4. 解析具体某一天的天气
            return parseDailyWeather(realLocationName, dayStr, dayOffset, response);

        } catch (Exception ex) {
            log.warn("Weather API failed, fallback to mock response: {}", ex.getMessage(), ex);
            return buildMockWeather("目标地点", "今天");
        }
    }

    /**
     * 调用高德 POI 接口将地名转为经纬度
     * 返回：[经度,纬度 , 高德官方标准名称]
     */
    private String[] getCoordinatesByAmap(RestClient client, String placeName) {
        try {
            Map<String, Object> response = client.get()
                    .uri(amapBaseUrl + "/v3/place/text?key={key}&keywords={keywords}&offset=1", amapApiKey, placeName)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response != null && "1".equals(response.get("status"))) {
                List<Map<String, Object>> pois = (List<Map<String, Object>>) response.get("pois");
                if (pois != null && !pois.isEmpty()) {
                    Map<String, Object> firstPoi = pois.get(0);
                    return new String[]{
                            (String) firstPoi.get("location"), // 格式 "108.83,34.12"
                            (String) firstPoi.get("name")
                    };
                }
            }
        } catch (Exception e) {
            log.error("Amap POI API error for location: {}", placeName, e);
        }
        return null; // 查不到返回null
    }

    /**
     * 解析 Open-Meteo 天气结果，翻译为大模型易懂的自然语言
     */
    private String parseDailyWeather(String location, String dayStr, int dayOffset, Map<String, Object> response) {
        try {
            Map<String, Object> daily = (Map<String, Object>) response.get("daily");

            List<String> times = (List<String>) daily.get("time");
            List<Number> maxTemps = (List<Number>) daily.get("temperature_2m_max");
            List<Number> minTemps = (List<Number>) daily.get("temperature_2m_min");
            List<Number> precipProbs = (List<Number>) daily.get("precipitation_probability_max");
            List<Number> weatherCodes = (List<Number>) daily.get("weather_code");

            // Open-Meteo 默认返回7天数据，防越界保护
            if (dayOffset >= times.size()) {
                dayOffset = 0;
            }

            String date = times.get(dayOffset);
            int maxTemp = maxTemps.get(dayOffset).intValue();
            int minTemp = minTemps.get(dayOffset).intValue();
            int precip = precipProbs.get(dayOffset).intValue();
            int code = weatherCodes.get(dayOffset).intValue();

            String weatherDesc = translateWeatherCode(code);

            return String.format("【%s】%s（%s）天气：%s，气温 %d℃ 到 %d℃，最大降水概率 %d%%。",
                    location, dayStr, date, weatherDesc, minTemp, maxTemp, precip);

        } catch (Exception e) {
            log.error("Failed to parse weather JSON", e);
            return location + dayStr + "的天气查询成功，但结果解析失败，请检查API数据结构。";
        }
    }

    /**
     * 从输入中提取地点，滤除干扰词
     */
    private String parseLocation(String query) {
        String cleanText = query.replaceAll("(今天|明天|后天|的|天气|怎么样|查询|帮我查|气温|多少度|预报|呢|啊)", "").trim();
        return cleanText.isEmpty() ? null : cleanText;
    }

    /**
     * 判断时间偏移量：0(今天)，1(明天)，2(后天)
     */
    private int parseTimeOffset(String query) {
        if (query.contains("后天")) return 2;
        if (query.contains("明天")) return 1;
        return 0; // 默认查询今天
    }

    /**
     * 将 WMO 天气代码翻译成人类可读的文字
     */
    private String translateWeatherCode(int code) {
        if (code == 0) return "晴朗";
        if (code == 1 || code == 2 || code == 3) return "多云转阴";
        if (code == 45 || code == 48) return "有雾";
        if (code >= 51 && code <= 55) return "毛毛雨";
        if (code >= 61 && code <= 65) return "下雨";
        if (code >= 71 && code <= 77) return "下雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code >= 95 && code <= 99) return "雷暴雨";
        return "未知天气(代码:" + code + ")";
    }

    private String buildMockWeather(String location, String day) {
        return day + "【" + location + "】降级数据：多云转小雨，18-24℃，降水概率60%。";
    }
}

//package com.student.agent.mcp;
//
//import java.util.Map;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.core.ParameterizedTypeReference;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.stereotype.Component;
//import org.springframework.web.client.RestClient;
//
//@Component
//public class WeatherMcpClient {
//
//    private static final Logger log = LoggerFactory.getLogger(WeatherMcpClient.class);
//
//    private final RestClient.Builder restClientBuilder;
//
//    @Value("${app.weather.base-url}")
//    private String baseUrl;
//
//    @Value("${app.weather.latitude}")
//    private String latitude;
//
//    @Value("${app.weather.longitude}")
//    private String longitude;
//
//    @Value("${app.weather.campus-name}")
//    private String campusName;
//
//    public WeatherMcpClient(RestClient.Builder restClientBuilder) {
//        this.restClientBuilder = restClientBuilder;
//    }
//
//    public String queryWeather(String query) {
//        try {
//            RestClient client = restClientBuilder.baseUrl(baseUrl).build();
//            Map<String, Object> response = client.get()
//                    .uri(uriBuilder -> uriBuilder.path("/v1/forecast")
//                            .queryParam("latitude", latitude)
//                            .queryParam("longitude", longitude)
//                            .queryParam("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
//                            .queryParam("timezone", "Asia/Shanghai")
//                            .build())
//                    .retrieve()
//                    .body(new ParameterizedTypeReference<>() {
//                    });
//            if (response == null) {
//                return buildMockWeather(query);
//            }
//            return campusName + "天气查询成功，原始结果：" + response;
//        } catch (Exception ex) {
//            log.warn("Weather API failed, fallback to mock response: {}", ex.getMessage());
//            return buildMockWeather(query);
//        }
//    }
//
//    private String buildMockWeather(String query) {
//        String day = query.contains("明天") ? "明天" : "今天";
//        return day + campusName + "多云转小雨，18-24℃，降水概率60%。建议随身带伞。";
//    }
//}
